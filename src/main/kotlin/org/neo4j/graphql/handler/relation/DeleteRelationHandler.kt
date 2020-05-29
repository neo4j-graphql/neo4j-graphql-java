package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import org.neo4j.graphql.*

class DeleteRelationHandler private constructor(
        type: GraphQLFieldsContainer,
        relation: RelationshipInfo,
        startId: RelationshipInfo.RelatedField,
        endId: RelationshipInfo.RelatedField,
        fieldDefinition: GraphQLFieldDefinition)
    : BaseRelationHandler(type, relation, startId, endId, fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : BaseRelationFactory("delete", schemaConfig) {
        override fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {
            if (!canHandleType(type)) {
                return
            }
            type.fieldDefinitions
                .filter { canHandleField(it) }
                .mapNotNull { targetField ->
                    buildFieldDefinition(type, targetField, true)
                        ?.let { builder -> buildingEnv.addOperation(MUTATION, builder.build()) }
                }
        }

        override fun createDataFetcher(
                sourceType: GraphQLFieldsContainer,
                relation: RelationshipInfo,
                startIdField: RelationshipInfo.RelatedField,
                endIdField: RelationshipInfo.RelatedField,
                fieldDefinition: GraphQLFieldDefinition
        ): DataFetcher<Cypher>? {
            return DeleteRelationHandler(sourceType, relation, startIdField, endIdField, fieldDefinition)
        }

    }

    override fun generateCypher(
            variable: String,
            field: Field,
            env: DataFetchingEnvironment): Cypher {
        val mapProjection = projectFields(variable, field, type, env, null)
        val arguments = field.arguments.map { it.name to it }.toMap()
        val startSelect = getRelationSelect(true, arguments)
        val endSelect = getRelationSelect(false, arguments)

        return Cypher("MATCH ${startSelect.query}" +
                " MATCH ${endSelect.query}" +
                " MATCH (${relation.startField})-[r:${relation.relType.quote()}]->(${relation.endField})" +
                " DELETE r" +
                " WITH DISTINCT ${relation.startField} AS $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                startSelect.params + endSelect.params)
    }

}
