package org.neo4j.graphql.handler

import graphql.language.InputValueDefinition
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.ResultStatement
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder
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
import org.neo4j.graphql.schema.model.outputs.NodeSelection
import org.neo4j.graphql.translate.ProjectionTranslator
import org.neo4j.graphql.translate.TopLevelMatchTranslator
import org.neo4j.graphql.translate.projection.createUnionQueries
import org.neo4j.graphql.utils.ResolveTree

/**
 * This class handles all the logic related to the querying of nodes.
 * This includes the augmentation of the query-fields and the related cypher generation
 */
internal class ReadResolver internal constructor(
    schemaConfig: SchemaConfig,
    val entity: Entity
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.EntityAugmentation {

        override fun augmentEntity(entity: Entity): List<AugmentedField> {
            if (entity.annotations.query?.read == false) {
                return emptyList()
            }
            val nodeType = entity.extractOnTarget(
                { node -> NodeSelection.Augmentation.generateNodeSelection(node, ctx) },
                { interfaze -> interfaze.name },
                { union -> union.name }

            )
                ?: return emptyList()
            val coordinates =
                addQueryField(
                    entity.namings.rootTypeFieldNames.read,
                    nodeType.asRequiredType().List.NonNull,
                    InputArguments.Augmentation(entity, ctx)
                )
            return AugmentedField(coordinates, ReadResolver(ctx.schemaConfig, entity)).wrapList()
        }
    }

    private class InputArguments(entity: Entity, args: Dict) {
        val where = args.nestedDict(Constants.WHERE)
            ?.let { WhereInput.create(entity, it) }

        val options = OptionsInput.create(entity as? ImplementingType, args.nestedDict(Constants.OPTIONS))

        class Augmentation(val entity: Entity, val ctx: AugmentationContext) : ArgumentsAugmentation {
            override fun augmentArguments(args: MutableList<InputValueDefinition>) {

                WhereInput.Augmentation.generateWhereIT(entity, ctx)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }

                OptionsInput.Augmentation
                    .generateOptionsIT(entity, ctx)
                    .let { args += inputValue(Constants.OPTIONS, it.asType()) }
            }
        }
    }

    override fun generateCypher(variable: String, env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()

        val resolveTree = ResolveTree.resolve(env)

        return entity.extractOnTarget(
            { node ->
                val input = InputArguments(node, resolveTree.args)

                val dslNode = node.asCypherNode(queryContext, variable)


                val optionsInput = input.options.merge(node)

                val ongoingReading = TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
                    .translateTopLevelMatch(
                        node,
                        dslNode,
                        input.where,
                        additionalPredicates = null,
                    )


                val projection = ProjectionTranslator()
                    .createProjectionAndParams(
                        node,
                        dslNode,
                        resolveTree,
                        schemaConfig,
                        queryContext
                    )

                val mapProjection = dslNode.project(projection.projection).`as`(dslNode.requiredSymbolicName)
                ongoingReading
                    .withSubQueries(projection.subQueriesBeforeSort)
                    .applySortingSkipAndLimit(dslNode, optionsInput, queryContext)
                    .withSubQueries(projection.subQueries)
                    .returning(mapProjection)
                    .build()
            },
            { interfaze ->
                createUnionRead(interfaze.implementations.values, resolveTree, interfaze, variable, queryContext)
            },
            { union ->
                createUnionRead(union.nodes.values, resolveTree, union, variable, queryContext)
            }
        )
    }

    private fun createUnionRead(
        nodes: Collection<Node>,
        resolveTree: ResolveTree,
        entity: Entity,
        variable: String,
        queryContext: QueryContext
    ): ResultStatement {
        val input = InputArguments(entity, resolveTree.args)

        val unionQueries = createUnionQueries(
            nodes,
            resolveTree,
            Cypher.name(variable),
            queryContext,
            schemaConfig,
            input.where,
            { node -> Cypher.match(node) }
        )

        val returnVariable = Cypher.name(variable)

        val ongoingReading: StatementBuilder.OngoingReading = when {
            unionQueries.size > 1 -> Cypher.call(Cypher.union(unionQueries))
            unionQueries.size == 1 -> Cypher.call(unionQueries.first())
            else -> error("No union queries found")
        }
        return ongoingReading
            .with(returnVariable)
            .applySortingSkipAndLimit(returnVariable, input.options.merge(entity as? ImplementingType), queryContext)
            .returning(returnVariable.`as`(variable))
            .build()
    }
}
