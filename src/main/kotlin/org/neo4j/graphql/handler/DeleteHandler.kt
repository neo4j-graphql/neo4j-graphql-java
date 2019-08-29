package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.schema.*
import org.neo4j.graphql.*

class DeleteHandler private constructor(
        type: GraphQLFieldsContainer,
        private val idField: GraphQLFieldDefinition,
        fieldDefinition: GraphQLFieldDefinition,
        private val isRelation: Boolean = type.isRelationType()
) : BaseDataFetcher(type, fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {
        override fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {
            if (!canHandle(type)) {
                return
            }
            val idField = type.fieldDefinitions.find { it.isID() } ?: return

            val fieldDefinition = buildingEnv
                .buildFieldDefinition("delete", type, listOf(idField), nullableResult = true)
                .description("Deletes ${type.name} and returns its ID on successful deletion")
                .type(type.ref() as GraphQLOutputType)
                .build()
            buildingEnv.addOperation(MUTATION, fieldDefinition)
        }

        override fun createDataFetcher(rootType: GraphQLObjectType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            if (rootType.name != MUTATION){
                return null
            }
            if (fieldDefinition.cypherDirective() != null) {
                return null
            }
            val type = fieldDefinition.type as? GraphQLFieldsContainer
                    ?: return null
            if (!canHandle(type)) {
                return null
            }
            val idField = type.fieldDefinitions.find { it.isID() } ?: return null
            return when {
                fieldDefinition.name == "delete${type.name}" -> DeleteHandler(type, idField, fieldDefinition)
                else -> null
            }
        }

        private fun canHandle(type: GraphQLFieldsContainer): Boolean {
            val typeName = type.name
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(typeName)) {
                return false
            }
            if (type.fieldDefinitions.find { it.isID() } == null) {
                return false
            }
            return true
        }
    }

    override fun generateCypher(variable: String, field: Field, projectionProvider: () -> Cypher, env: DataFetchingEnvironment): Cypher {
        val idArg = field.arguments.first { it.name == idField.name }
        val mapProjection = projectionProvider.invoke()

        val select = getSelectQuery(variable, label(), idArg, idField, isRelation)
        return Cypher("MATCH " + select.query +
                " WITH $variable as toDelete, " +
                "${mapProjection.query} AS $variable" +
                " DETACH DELETE toDelete" +
                " RETURN $variable",
                select.params + mapProjection.params)
    }

}
