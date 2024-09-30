package org.neo4j.graphql.handler

import graphql.language.InputValueDefinition
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Entity
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.ArgumentsAugmentation
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.options.OptionsInput
import org.neo4j.graphql.schema.model.inputs.options.SortInput
import org.neo4j.graphql.schema.model.outputs.root_connection.RootNodeConnectionSelection
import org.neo4j.graphql.translate.ProjectionTranslator
import org.neo4j.graphql.translate.TopLevelMatchTranslator
import org.neo4j.graphql.utils.ResolveTree

/**
 * This class handles all the logic related to the querying of nodes.
 * This includes the augmentation of the query-fields and the related cypher generation
 */
internal class ConnectionResolver private constructor(
    schemaConfig: SchemaConfig,
    val implementingType: ImplementingType
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.EntityAugmentation {

        override fun augmentEntity(entity: Entity): List<AugmentedField> {
            if (entity !is ImplementingType || entity.annotations.query?.read == false) {
                return emptyList()
            }

            val nodeConnectionType =
                RootNodeConnectionSelection.Augmentation.generateImplementingTypeConnectionOT(entity, ctx)
            val coordinates = addQueryField(
                entity.namings.rootTypeFieldNames.connection,
                nodeConnectionType.asRequiredType(),
                InputArguments.Augmentation(entity, ctx)
            )
            return AugmentedField(coordinates, ConnectionResolver(ctx.schemaConfig, entity)).wrapList()
        }
    }

    private class InputArguments(implementingType: ImplementingType, args: Dict) {

        val where = args.nestedDict(Constants.WHERE)
            ?.let { where ->
                implementingType.extractOnImplementingType(
                    onNode = { WhereInput.NodeWhereInput(it, where) },
                    onInterface = { WhereInput.InterfaceWhereInput(it, where) }
                )
            }

        val options = OptionsInput.create(
            args,
            limitName = Constants.FIRST,
            offsetName = Constants.AFTER,
            sortName = Constants.SORT,
            { SortInput.create(implementingType, it) }
        )

        class Augmentation(val implementingType: ImplementingType, val ctx: AugmentationContext) :
            ArgumentsAugmentation {
            override fun augmentArguments(args: MutableList<InputValueDefinition>) {

                WhereInput.Augmentation
                    .generateWhereIT(implementingType, ctx)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }

                SortInput.Companion.Augmentation
                    .generateSortIT(implementingType, ctx)
                    ?.let { args += inputValue(Constants.SORT, it.List) }

                args += inputValue(Constants.FIRST, Constants.Types.Int)
                args += inputValue(Constants.AFTER, Constants.Types.String)
            }
        }
    }

    override fun generateCypher(variable: String, env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()
        if (implementingType !is Node) {
            TODO()
        }
        val node: Node = implementingType


        val resolveTree = ResolveTree.resolve(env)

        val input = InputArguments(node, resolveTree.args)

        val dslNode = node.asCypherNode(queryContext, variable)

        val ongoingReading = TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
            .translateTopLevelMatch(
                node,
                dslNode,
                input.where,
                additionalPredicates = null,
            )


        val topProjection = mutableListOf<Any>()
        val connectionSelection = resolveTree.fieldsByTypeName[implementingType.namings.rootTypeSelection.connection]
        val subQueries = mutableListOf<Statement>()

        val edges = Cypher.name("edges")
        val totalCount = Cypher.name("totalCount")

        connectionSelection?.forEachField(Constants.EDGES_FIELD) { edgesSelection ->
            val edgesProjection = mutableListOf<Any>()
            val edgeSelection = edgesSelection.fieldsByTypeName[implementingType.namings.rootTypeSelection.edge]

            val alias = queryContext.getNextVariable(edgesSelection.aliasOrName)


            edgeSelection?.forEachField(Constants.CURSOR_FIELD) {
                TODO()
            }
            val subQueriesBeforeSort = mutableListOf<Statement>()
            val subQueriesAfterSort = mutableListOf<Statement>()

            edgeSelection?.forEachField(Constants.NODE_FIELD) { nodeSelection ->
                val nodeProjection = ProjectionTranslator()
                    .createProjectionAndParams(
                        node,
                        dslNode,
                        nodeSelection,
                        schemaConfig,
                        queryContext,
                        resolveType = true,
                        useShortcut = false, // TODO this can be enabled after migration
                    )

                subQueriesBeforeSort += nodeProjection.subQueriesBeforeSort
                subQueriesAfterSort += nodeProjection.subQueries

                edgesProjection += nodeSelection.aliasOrName
                edgesProjection += Cypher.mapOf(*nodeProjection.projection.toTypedArray())
            }

            val edge = Cypher.name("edge")

            subQueries += Cypher.with(edges)
                .unwind(edges).`as`(edge)
                .with(edge.property(Constants.NODE_FIELD).`as`(dslNode.requiredSymbolicName))
                .withSubQueries(subQueriesBeforeSort)
                .applySortingSkipAndLimit(
                    dslNode,
                    input.options,
                    queryContext,
                    enforceAsterix = true
                )
                .withSubQueries(subQueriesAfterSort)
                .returning(Cypher.collect(Cypher.mapOf(*edgesProjection.toTypedArray())).`as`(alias))
                .build()

            topProjection += edgesSelection.aliasOrName
            topProjection += alias
        }

        if (connectionSelection?.getAliasOfField(Constants.TOTAL_COUNT) != null) {
            connectionSelection.forEachField(Constants.TOTAL_COUNT) { totalSelection ->
                topProjection += totalSelection.aliasOrName
                topProjection += totalCount
            }
        } else {
            // TODO optimize / remove this after migration from js is completed
            topProjection += Constants.TOTAL_COUNT
            topProjection += totalCount
        }

        return ongoingReading
            .with(
                Cypher.collect(
                    Cypher.mapOf(Constants.NODE_FIELD, dslNode.asExpression())
                ).`as`(edges)
            )
            .with(edges, Cypher.size(edges).`as`(totalCount))
            .withSubQueries(subQueries)
            .returning(Cypher.mapOf(*topProjection.toTypedArray()).`as`(variable))
            .build()
    }
}
