package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.schema.*
import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingUpdate
import org.neo4j.graphql.*

class DeleteHandler private constructor(
        type: GraphQLFieldsContainer,
        private val idField: GraphQLFieldDefinition,
        fieldDefinition: GraphQLFieldDefinition,
        private val isRelation: Boolean = type.isRelationType()
) : BaseDataFetcherForContainer(type, fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {
        override fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {
            if (!canHandle(type)) {
                return
            }
            val idField = type.getIdField() ?: return

            val fieldDefinition = buildingEnv
                .buildFieldDefinition("delete", type, listOf(idField), nullableResult = true)
                .description("Deletes ${type.name} and returns the type itself")
                .type(type.ref() as GraphQLOutputType)
                .build()
            buildingEnv.addMutationField(fieldDefinition)
        }

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.MUTATION) {
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
            val idField = type.getIdField() ?: return null
            return when (fieldDefinition.name) {
                "delete${type.name}" -> DeleteHandler(type, idField, fieldDefinition)
                else -> null
            }
        }

        private fun canHandle(type: GraphQLFieldsContainer): Boolean {
            val typeName = type.name
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(typeName)) {
                return false
            }
            return type.getIdField() != null
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val idArg = field.arguments.first { it.name == idField.name }

        val (propertyContainer, where) = getSelectQuery(variable, type.label(), idArg, idField, isRelation)
        val select = if (isRelation) {
            val rel = propertyContainer as? Relationship
                    ?: throw IllegalStateException("Expect a Relationship but got ${propertyContainer.javaClass.name}")
            org.neo4j.cypherdsl.core.Cypher.match(rel)
                .where(where)
        } else {
            val node = propertyContainer as? Node
                    ?: throw IllegalStateException("Expect a Node but got ${propertyContainer.javaClass.name}")
            org.neo4j.cypherdsl.core.Cypher.match(node)
                .where(where)
        }
        val deletedElement = propertyContainer.requiredSymbolicName.`as`("toDelete")
        val mapProjection = projectFields(propertyContainer, field, type, env)

        val projection = propertyContainer.project(mapProjection).`as`(variable)
        val update: OngoingUpdate = select.with(deletedElement, projection)
            .detachDelete(deletedElement)
        return update
            .returning(projection.asName().`as`(field.aliasOrName()))
            .build()
    }

}
