package org.neo4j.graphql.handler.v2

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.schema.AugmentationHandlerV2


class AggregateResolver private constructor(
    val schemaConfig: SchemaConfig,
    val node: Node
) : DataFetcher<OldCypher> {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): AugmentedField? {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.READ)) {
                return null
            }
            val aggregationSelection = addAggregationSelectionType(node)
            val coordinates =
                addQueryField(node.plural + "Aggregate", aggregationSelection.asRequiredType()) { args ->
                    generateWhereIT(node)?.let { args += inputValue(Constants.WHERE, it.asType()) }
                    generateFulltextIT(node)?.let { args += inputValue(Constants.FULLTEXT, it.asType()) }
                }
            return AugmentedField(coordinates, AggregateResolver(ctx.schemaConfig, node))
        }
    }

    override fun get(environment: DataFetchingEnvironment?): OldCypher {
        TODO("Not yet implemented")
    }
}

