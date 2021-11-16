package org.neo4j.graphql.handler

import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.TypeDefinitionRegistry
import org.atteo.evo.inflector.English
import org.neo4j.cypherdsl.core.Cypher.*
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.filter.OptimizedFilterHandler

/**
 * This class handles all the logic related to the querying of nodes and relations.
 * This includes the augmentation of the query-fields and the related cypher generation
 */
class QueryHandler private constructor(schemaConfig: SchemaConfig) : BaseDataFetcherForContainer(schemaConfig) {

    class Factory(schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        override fun augmentType(type: ImplementingTypeDefinition<*>) {
            if (!canHandle(type)) {
                return
            }
            val typeName = type.name
            val relevantFields = getRelevantFields(type)

            val filterTypeName = addFilterType(type)
            val arguments = if (schemaConfig.useWhereFilter) {
                listOf(input(WHERE, TypeName(filterTypeName)))
            } else {
                getInputValueDefinitions(relevantFields, true, { true }) +
                        input(FILTER, TypeName(filterTypeName))
            }

            var fieldName = if (schemaConfig.capitalizeQueryFields) typeName else typeName.decapitalize()
            if (schemaConfig.pluralizeFields) {
                fieldName = English.plural(fieldName)
            }
            val builder = FieldDefinition
                .newFieldDefinition()
                .name(fieldName)
                .inputValueDefinitions(arguments.toMutableList())
                .type(NonNullType(ListType(NonNullType(TypeName(type.name)))))

            if (schemaConfig.queryOptionStyle == SchemaConfig.InputStyle.INPUT_TYPE) {
                val optionsTypeName = addOptions(type)
                builder.inputValueDefinition(input(OPTIONS, TypeName(optionsTypeName)))
            } else {
                builder
                    .inputValueDefinition(input(FIRST, TypeInt))
                    .inputValueDefinition(input(OFFSET, TypeInt))

                val orderingTypeName = addOrdering(type)
                if (orderingTypeName != null) {
                    builder.inputValueDefinition(input(ORDER_BY, ListType(NonNullType(TypeName(orderingTypeName)))))
                }
            }
            val def = builder.build()
            addQueryField(def)
        }

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: FieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.QUERY) {
                return null
            }
            val cypherDirective = fieldDefinition.cypherDirective()
            if (cypherDirective != null) {
                return null
            }
            val type = fieldDefinition.type.inner().resolve() as? ImplementingTypeDefinition<*> ?: return null
            if (!canHandle(type)) {
                return null
            }
            return QueryHandler(schemaConfig)
        }

        private fun canHandle(type: ImplementingTypeDefinition<*>): Boolean {
            val typeName = type.name
            if (!schemaConfig.query.enabled || schemaConfig.query.exclude.contains(typeName) || isRootType(type)) {
                return false
            }
            if (getRelevantFields(type).isEmpty() && !hasRelationships(type)) {
                return false
            }
            return true
        }

        private fun hasRelationships(type: ImplementingTypeDefinition<*>): Boolean = type.fieldDefinitions
            .filterNot { it.isIgnored() }
            .any { it.isRelationship() }

        private fun getRelevantFields(type: ImplementingTypeDefinition<*>): List<FieldDefinition> {
            return type
                .getScalarFields()
                .filter { it.dynamicPrefix() == null } // TODO currently we do not support filtering on dynamic properties
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val fieldDefinition = env.fieldDefinition
        val type = env.typeAsContainer()

        val (propertyContainer, match) = when {
            type.isRelationType() -> anyNode().relationshipTo(anyNode(), type.label()).named(variable)
                .let { rel -> rel to match(rel) }
            else -> node(type.label()).named(variable)
                .let { node -> node to match(node) }
        }

        val ongoingReading = if ((env.getContext() as? QueryContext)?.optimizedQuery?.contains(QueryContext.OptimizationStrategy.FILTER_AS_MATCH) == true) {

            OptimizedFilterHandler(type, schemaConfig).generateFilterQuery(variable, fieldDefinition, env.arguments, match, propertyContainer, env.variables)

        } else {

            val where = where(propertyContainer, fieldDefinition, type, env.arguments, env.variables)
            match.where(where)
        }

        val (projectionEntries, subQueries) = projectFields(propertyContainer, type, env)
        val mapProjection = propertyContainer.project(projectionEntries).`as`(field.aliasOrName())
        return ongoingReading
            .withSubQueries(subQueries)
            .returning(mapProjection)
            .skipLimitOrder(propertyContainer.requiredSymbolicName, fieldDefinition, env.arguments)
            .build()
    }
}
