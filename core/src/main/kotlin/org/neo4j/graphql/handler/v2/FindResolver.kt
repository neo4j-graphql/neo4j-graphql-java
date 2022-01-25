package org.neo4j.graphql.handler.v2

import graphql.language.ListType
import graphql.language.NonNullType
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.schema.AugmentationHandlerV2

/**
 * This class handles all the logic related to the querying of nodes.
 * This includes the augmentation of the query-fields and the related cypher generation
 */
class FindResolver private constructor(
    val schemaConfig: SchemaConfig,
    val node: Node
) : DataFetcher<Cypher> {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): AugmentedField? {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.READ)) {
                return null
            }
            val nodeType = generateNodeOT(node) ?: return null
            val coordinates =
                addQueryField(node.plural.decapitalize(), NonNullType(ListType(nodeType.asRequiredType()))) { args ->
                    generateWhereIT(node)?.let { args += inputValue(Constants.WHERE, it.asType()) }
                    generateOptionsIT(node).let { args += inputValue(Constants.OPTIONS, it.asType()) }
                    generateFulltextIT(node)?.let { args += inputValue(Constants.FULLTEXT, it.asType()) }
                }
            return AugmentedField(coordinates, FindResolver(ctx.schemaConfig, node))
        }
    }

    override fun get(environment: DataFetchingEnvironment?): Cypher {
        TODO("Not yet implemented")
    }
}

