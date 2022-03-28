package org.neo4j.graphql.handler.v2

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.schema.AugmentationHandlerV2

class UpdateResolver private constructor(
    val schemaConfig: SchemaConfig,
    val node: Node
) : DataFetcher<OldCypher> {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): AugmentedField? {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.UPDATE)) {
                return null
            }

            val responseType = addResponseType("Update", node)
            val coordinates = addMutationField("update" + node.pascalCasePlural, responseType.asRequiredType()) { args ->
                generateWhereIT(node)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }

                generateContainerUpdateIT(node)
                    ?.let { args += inputValue(Constants.UPDATE_FIELD, it.asType()) }

                generateContainerConnectInputIT(node)
                    ?.let { args += inputValue(Constants.CONNECT_FIELD, it.asType()) }

                generateContainerDisconnectInputIT(node)
                    ?.let { args += inputValue(Constants.DISCONNECT_FIELD, it.asType()) }

                generateContainerRelationCreateInputIT(node)
                    ?.let { args += inputValue(Constants.CREATE_FIELD, it.asType()) }

                generateContainerDeleteInputIT(node)
                    ?.let { args += inputValue(Constants.DELETE_FIELD, it.asType()) }

                generateContainerConnectOrCreateInputIT(node)
                    ?.let { args += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.asType()) }
            }

            return AugmentedField(coordinates, UpdateResolver(ctx.schemaConfig, node))
        }
    }

    override fun get(environment: DataFetchingEnvironment?): OldCypher {
        TODO("Not yet implemented")
    }
}

