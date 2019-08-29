package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.schema.*
import org.neo4j.graphql.*

class CreateRelationTypeHandler private constructor(
        type: GraphQLFieldsContainer,
        relation: RelationshipInfo,
        startId: RelationshipInfo.RelatedField,
        endId: RelationshipInfo.RelatedField,
        fieldDefinition: GraphQLFieldDefinition)
    : BaseRelationHandler(type, relation, startId, endId, fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {
        override fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {
            if (!canHandle(type)) {
                return
            }
            val relation = type.relationship()!!
            val startIdField = relation.getStartFieldId()
            val endIdField = relation.getEndFieldId()
            if (startIdField == null || endIdField == null) {
                return
            }
            val relevantFields = getRelevantFields(type)

            val createArgs = getRelevantFields(type)
                .filter { !it.isNativeId() }
                .filter { it.name != startIdField.argumentName }
                .filter { it.name != endIdField.argumentName }

            val builder = buildingEnv
                .buildFieldDefinition("create", type, relevantFields, nullableResult = false)
                .argument(input(startIdField.argumentName, startIdField.field.type))
                .argument(input(endIdField.argumentName, endIdField.field.type))

            createArgs.forEach { builder.argument(input(it.name, it.type)) }

            buildingEnv.addOperation(MUTATION, builder.build())
        }

        override fun createDataFetcher(rootType: GraphQLObjectType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            if (rootType.name != MUTATION) {
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
            if (fieldDefinition.name != "create${type.name}") {
                return null
            }

            val relation = type.relationship() ?: return null
            val startIdField = relation.getStartFieldId() ?: return null
            val endIdField = relation.getEndFieldId() ?: return null

            return CreateRelationTypeHandler(type, relation, startIdField, endIdField, fieldDefinition)
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
            val relation = type.relationship() ?: return false
            val startIdField = relation.getStartFieldId()
            val endIdField = relation.getEndFieldId()
            if (startIdField == null || endIdField == null) {
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

    override fun generateCypher(
            variable: String,
            field: Field,
            projectionProvider: () -> Cypher,
            env: DataFetchingEnvironment
    ): Cypher {
        val properties = properties(variable, field.arguments)
        val mapProjection = projectionProvider.invoke()

        val arguments = field.arguments.map { it.name to it }.toMap()
        val startSelect = getRelationSelect(true, arguments)
        val endSelect = getRelationSelect(false, arguments)

        return Cypher("MATCH ${startSelect.query}" +
                " MATCH ${endSelect.query}" +
                " CREATE (${relation.startField})-[$variable:${relation.relType.quote()}${properties.query}]->(${relation.endField})" +
                " WITH $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                startSelect.params + endSelect.params + properties.params)
    }
}
