package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.schema.*
import org.neo4j.graphql.*

class CreateTypeHandler private constructor(
        type: GraphQLFieldsContainer,
        fieldDefinition: GraphQLFieldDefinition
) : BaseDataFetcher(type, fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {
        override fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {
            if (!canHandle(type)) {
                return
            }
            val relevantFields = getRelevantFields(type)
            val fieldDefinition = buildingEnv
                .buildFieldDefinition("create", type, relevantFields, nullableResult = false)
                .build()

            buildingEnv.addMutationField(fieldDefinition)
        }

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.MUTATION){
                return null
            }
            if (fieldDefinition.cypherDirective() != null) {
                return null
            }
            val type = fieldDefinition.type.inner() as? GraphQLObjectType
                    ?: return null
            if (!canHandle(type)) {
                return null
            }
            return when {
                fieldDefinition.name == "create${type.name}" -> CreateTypeHandler(type, fieldDefinition)
                else -> null
            }
        }

        private fun getRelevantFields(type: GraphQLFieldsContainer): List<GraphQLFieldDefinition> {
            return type
                .relevantFields()
                .filter { !it.isNativeId() }
        }

        private fun canHandle(type: GraphQLFieldsContainer): Boolean {
            val typeName = type.name
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(typeName)) {
                return false
            }
            if (type !is GraphQLObjectType) {
                return false
            }
            if ((type as GraphQLDirectiveContainer).isRelationType()) {
                // relations are handled by the CreateRelationTypeHandler
                return false
            }

            if (getRelevantFields(type).isEmpty()) {
                // nothing to create
                // TODO or should we support just creating empty nodes?
                return false
            }
            return true
        }

    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Cypher {
        val properties = properties(variable, field.arguments)
        val mapProjection = projectFields(variable, field, type, env, null)
        return Cypher("CREATE ($variable:${allLabels()}${properties.query})" +
                " WITH $variable" +
                " RETURN ${mapProjection.query} AS ${field.aliasOrName()}",
                (mapProjection.params + properties.params))
    }

}
