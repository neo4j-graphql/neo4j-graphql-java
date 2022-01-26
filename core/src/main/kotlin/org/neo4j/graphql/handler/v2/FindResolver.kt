package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationHandlerV2
import org.neo4j.graphql.translate.TopLevelMatchTranslator

/**
 * This class handles all the logic related to the querying of nodes.
 * This includes the augmentation of the query-fields and the related cypher generation
 */
class FindResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

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

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val fieldDefinition = env.fieldDefinition
        val type = env.typeAsContainer()

        val (propertyContainer, match) = when {
            type.isRelationType() -> Cypher.anyNode()
                .relationshipTo(Cypher.anyNode(), type.label()).named(variable)
                .let { rel -> rel to Cypher.match(rel) }
            else -> Cypher.node(type.label()).named(variable)
                .let { node -> node to Cypher.match(node) }
        }

        val ongoingReading = TopLevelMatchTranslator(schemaConfig, env.variables).translateTopLevelMatch(node, variable, env.arguments)

//        val ongoingReading =
//            if ((env.getContext() as? QueryContext)?.optimizedQuery?.contains(QueryContext.OptimizationStrategy.FILTER_AS_MATCH) == true) {
//
//                OptimizedFilterHandler(type, schemaConfig).generateFilterQuery(
//                    variable,
//                    fieldDefinition,
//                    env.arguments,
//                    match,
//                    propertyContainer,
//                    env.variables
//                )
//
//            } else {
//
//                val where = TopLevelMatchTranslator.where(propertyContainer, node, variable,  env.arguments, env.variables, schemaConfig)
//                match.where(where)
//            }

        val (projectionEntries, subQueries) = projectFields(propertyContainer, type, env)
        val mapProjection = propertyContainer.project(projectionEntries).`as`(field.aliasOrName())
        return ongoingReading
            .withSubQueries(subQueries)
            .returning(mapProjection)
            .skipLimitOrder(propertyContainer.requiredSymbolicName, fieldDefinition, env.arguments)
            .build()
    }
}

