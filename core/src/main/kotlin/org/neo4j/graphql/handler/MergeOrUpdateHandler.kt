package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import org.neo4j.graphql.*

class MergeOrUpdateHandler private constructor(
        type: GraphQLFieldsContainer,
        private val merge: Boolean,
        private val idField: GraphQLFieldDefinition,
        fieldDefinition: GraphQLFieldDefinition,
        private val isRelation: Boolean = type.isRelationType()
) : BaseDataFetcherForContainer(type, fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {
        override fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {
            if (!canHandle(type)) {
                return
            }

            val relevantFields = type.relevantFields()
            val mergeField = buildingEnv
                .buildFieldDefinition("merge", type, relevantFields, nullableResult = false)
                .build()
            buildingEnv.addMutationField(mergeField)

            val updateField = buildingEnv
                .buildFieldDefinition("update", type, relevantFields, nullableResult = true)
                .build()
            buildingEnv.addMutationField(updateField)
        }

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.MUTATION) {
                return null
            }
            if (fieldDefinition.cypherDirective() != null) {
                return null
            }
            val type = fieldDefinition.type.inner() as? GraphQLFieldsContainer
                    ?: return null
            if (!canHandle(type)) {
                return null
            }
            val idField = type.getIdField() ?: return null
            return when {
                fieldDefinition.name == "merge${type.name}" -> MergeOrUpdateHandler(type, true, idField, fieldDefinition)
                fieldDefinition.name == "update${type.name}" -> MergeOrUpdateHandler(type, false, idField, fieldDefinition)
                else -> null
            }
        }

        private fun canHandle(type: GraphQLFieldsContainer): Boolean {
            val typeName = type.name
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(typeName)) {
                return false
            }
            if (type.getIdField() == null) {
                return false
            }
            if (type.relevantFields().none { !it.isID() }) {
                // nothing to update (except ID)
                return false
            }
            return true
        }
    }

    init {
        defaultFields.clear() // for merge or updates we do not reset to defaults
        propertyFields.remove(idField.name) // id should not be updated
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Cypher {
        val idArg = field.arguments.first { it.name == idField.name }

        val properties = properties(variable, field.arguments)
        val mapProjection = projectFields(variable, field, type, env, null)

        val select = getSelectQuery(variable, label(), idArg, idField, isRelation)
        return Cypher((if (merge && !idField.isNativeId()) "MERGE " else "MATCH ") + select.query +
                " SET $variable += " + properties.query +
                " WITH $variable" +
                " RETURN ${mapProjection.query} AS ${field.aliasOrName()}",
                select.params + properties.params + mapProjection.params)
    }
}
