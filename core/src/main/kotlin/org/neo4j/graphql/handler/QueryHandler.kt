package org.neo4j.graphql.handler

import graphql.Scalars
import graphql.language.Field
import graphql.schema.*
import org.neo4j.cypherdsl.core.Cypher.*
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.filter.OptimizedFilterHandler

/**
 * This class handles all the logic related to the querying of nodes and relations.
 * This includes the augmentation of the query-fields and the related cypher generation
 */
class QueryHandler private constructor(
        type: GraphQLFieldsContainer,
        fieldDefinition: GraphQLFieldDefinition)
    : BaseDataFetcherForContainer(type, fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {
        override fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {
            if (!canHandle(type)) {
                return
            }
            val typeName = type.name
            val relevantFields = getRelevantFields(type)

            // TODO not just generate the input type but use it as well
            buildingEnv.addInputType("_${typeName}Input", type.relevantFields())
            val filterTypeName = buildingEnv.addFilterType(type)

            val builder = GraphQLFieldDefinition
                .newFieldDefinition()
                .name(if (schemaConfig.capitalizeQueryFields) typeName else typeName.decapitalize())
                .arguments(buildingEnv.getInputValueDefinitions(relevantFields) { true })
                .argument(input(FILTER, GraphQLTypeReference(filterTypeName)))
                .type(GraphQLNonNull(GraphQLList(GraphQLNonNull(GraphQLTypeReference(type.name)))))

            if (schemaConfig.queryOptionStyle == SchemaConfig.InputStyle.INPUT_TYPE) {
                val optionsTypeName = buildingEnv.addOptions(type)
                val optionsType = GraphQLTypeReference(optionsTypeName)
                builder.argument(input(OPTIONS, optionsType))
            } else {
                builder
                    .argument(input(FIRST, Scalars.GraphQLInt))
                    .argument(input(OFFSET, Scalars.GraphQLInt))

                val orderingTypeName = buildingEnv.addOrdering(type)
                if (orderingTypeName != null) {
                    val orderType = GraphQLList(GraphQLNonNull(GraphQLTypeReference(orderingTypeName)))
                    builder.argument(input(ORDER_BY, orderType))
                }
            }
            val def = builder.build()
            buildingEnv.addQueryField(def)
        }

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.QUERY) {
                return null
            }
            val cypherDirective = fieldDefinition.cypherDirective()
            if (cypherDirective != null) {
                return null
            }
            val type = fieldDefinition.type.inner() as? GraphQLFieldsContainer
                    ?: return null
            if (!canHandle(type)) {
                return null
            }
            return QueryHandler(type, fieldDefinition)
        }

        private fun canHandle(type: GraphQLFieldsContainer): Boolean {
            val typeName = type.innerName()
            if (!schemaConfig.query.enabled || schemaConfig.query.exclude.contains(typeName)) {
                return false
            }
            if (getRelevantFields(type).isEmpty() && !hasRelationships(type)) {
                return false
            }
            return true
        }

        private fun hasRelationships(type: GraphQLFieldsContainer): Boolean = type.fieldDefinitions.any { it.isRelationship() }

        private fun getRelevantFields(type: GraphQLFieldsContainer): List<GraphQLFieldDefinition> {
            return type
                .relevantFields()
                .filter { it.dynamicPrefix() == null } // TODO currently we do not support filtering on dynamic properties
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {

        val (propertyContainer, match) = when {
            type.isRelationType() -> anyNode().relationshipTo(anyNode(), type.label()).named(variable)
                .let { rel -> rel to match(rel) }
            else -> node(type.label()).named(variable)
                .let { node -> node to match(node) }
        }

        val ongoingReading = if ((env.getContext() as? QueryContext)?.optimizedQuery?.contains(QueryContext.OptimizationStrategy.FILTER_AS_MATCH) == true) {

            OptimizedFilterHandler(type).generateFilterQuery(variable, fieldDefinition, field, match, propertyContainer, env.variables)

        } else {

            val where = where(propertyContainer, fieldDefinition, type, field, env.variables)
            match.where(where)
        }

        val ordering = orderBy(propertyContainer, field.arguments, fieldDefinition, env.variables)
        val skipLimit = SkipLimit(variable, field.arguments, fieldDefinition)

        val projectionEntries = projectFields(propertyContainer, field, type, env)
        val mapProjection = propertyContainer.project(projectionEntries).`as`(field.aliasOrName())
        val resultWithSkipLimit = ongoingReading.returning(mapProjection)
            .let {
                val orderedResult = ordering?.let { o -> it.orderBy(*o.toTypedArray()) } ?: it
                skipLimit.format(orderedResult)
            }

        return resultWithSkipLimit.build()
    }
}
