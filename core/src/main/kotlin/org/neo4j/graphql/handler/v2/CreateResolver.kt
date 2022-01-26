package org.neo4j.graphql.handler.v2

import graphql.language.ListType
import graphql.language.NonNullType
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.schema.AugmentationHandlerV2

class CreateResolver private constructor(
    val schemaConfig: SchemaConfig,
    val node: Node
) : DataFetcher<OldCypher> {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): AugmentedField? {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.CREATE)) {
                return null
            }

            val coordinates = generateContainerCreateInputIT(node)?.let { inputType ->
                val responseType = addResponseType("Create", node)
                addMutationField("create" + node.plural, responseType.asRequiredType()) { args ->
                    args += inputValue(Constants.INPUT_FIELD, NonNullType(ListType(inputType.asRequiredType())))
                }
            } ?: return null

            return AugmentedField(coordinates, CreateResolver(ctx.schemaConfig, node))
        }
    }

    override fun get(environment: DataFetchingEnvironment?): OldCypher {
        TODO("Not yet implemented")
    }
}

