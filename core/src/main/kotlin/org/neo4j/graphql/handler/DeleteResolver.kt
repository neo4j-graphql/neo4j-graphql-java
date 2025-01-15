package org.neo4j.graphql.handler

import graphql.language.InputValueDefinition
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.driver.adapter.Neo4jAdapter
import org.neo4j.graphql.schema.ArgumentsAugmentation
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.translate.TopLevelMatchTranslator
import org.neo4j.graphql.utils.ResolveTree

internal class DeleteResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.NodeAugmentation {

        override fun augmentNode(node: Node): List<AugmentedField> {
            if (node.annotations.mutation?.delete == false) {
                return emptyList()
            }
            val coordinates = addMutationField(
                node.namings.rootTypeFieldNames.delete,
                Constants.Types.DeleteInfo.makeRequired(),
                DeleteInputArguments.Augmentation(node, ctx)
            ) ?: return emptyList()

            return AugmentedField(coordinates, DeleteResolver(ctx.schemaConfig, node)).wrapList()
        }
    }

    private class DeleteInputArguments(node: Node, args: Dict) {
        val where = args.nestedDict(Constants.WHERE)
            ?.let { WhereInput.NodeWhereInput(node, it) }

        class Augmentation(val node: Node, val ctx: AugmentationContext) : ArgumentsAugmentation {

            override fun augmentArguments(args: MutableList<InputValueDefinition>) {
                WhereInput.NodeWhereInput.Augmentation
                    .generateWhereIT(node, ctx)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }
            }
        }
    }


    override fun generateCypher(env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()
        val resolveTree = ResolveTree.resolve(env)
        val input = DeleteInputArguments(node, resolveTree.args)

        val dslNode = node.asCypherNode(queryContext, RESULT_VARIABLE)

        val ongoingReading =
            TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
                .translateTopLevelMatch(
                    node,
                    dslNode,
                    input.where
                )

        return ongoingReading
            .detachDelete(dslNode)
            .build()
    }

    override fun mapResult(env: DataFetchingEnvironment, result: Neo4jAdapter.QueryResult): Any {
        return result.statistics
    }
}

