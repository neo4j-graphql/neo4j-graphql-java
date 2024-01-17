package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Model
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler

class GlobalNodeResolver private constructor(
    schemaConfig: SchemaConfig,
    val globalNodes: List<Node>,
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.ModelAugmentation {

        override fun augmentModel(model: Model): List<AugmentedField> {
            val globalNodes = model.nodes.filter { it.hasRelayId }
            if (globalNodes.isEmpty()) {
                return emptyList()
            }
            val coordinates = addQueryField(Constants.NODE_FIELD, Constants.Types.Node, {
                description("Fetches an object given its ID.".asDescription())
            }) {
                it += inputValue(Constants.ID_FIELD, Constants.Types.ID.NonNull)
            }
            return AugmentedField(coordinates, GlobalNodeResolver(ctx.schemaConfig, globalNodes)).wrapList()
        }

    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        TODO()
    }
}
