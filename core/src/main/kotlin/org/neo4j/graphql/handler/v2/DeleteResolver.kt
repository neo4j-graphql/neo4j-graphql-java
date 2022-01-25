package org.neo4j.graphql.handler.v2

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.schema.AugmentationHandlerV2

class DeleteResolver private constructor(
    val schemaConfig: SchemaConfig,
    val node: Node
) : DataFetcher<Cypher> {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): AugmentedField? {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.DELETE)) {
                return null
            }

            val coordinates =
                addMutationField("delete" + node.plural, Constants.Types.DeleteInfo.makeRequired()) { args ->
                    generateWhereIT(node)?.let { args += inputValue(Constants.WHERE, it.asType()) }
                    generateContainerDeleteInputIT(node)?.let {
                        args += inputValue(Constants.DELETE_FIELD, it.asType())
                    }
                }

            return AugmentedField(coordinates, DeleteResolver(ctx.schemaConfig, node))
        }
    }

    override fun get(environment: DataFetchingEnvironment?): Cypher {
        TODO("Not yet implemented")
    }
}

