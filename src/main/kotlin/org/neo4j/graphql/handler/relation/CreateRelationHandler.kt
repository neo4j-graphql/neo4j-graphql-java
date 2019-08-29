package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.schema.*
import org.neo4j.graphql.*

class CreateRelationHandler private constructor(
        type: GraphQLFieldsContainer,
        relation: RelationshipInfo,
        startId: RelationshipInfo.RelatedField,
        endId: RelationshipInfo.RelatedField,
        fieldDefinition: GraphQLFieldDefinition)
    : BaseRelationHandler(type, relation, startId, endId, fieldDefinition) {

    class Factory(schemaConfig: SchemaConfig) : BaseRelationFactory("add", schemaConfig) {
        override fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {
            if (!canHandleType(type)) {
                return
            }
            type.fieldDefinitions
                .filter { canHandleField(it) }
                .mapNotNull { targetField ->
                    buildFieldDefinition(type, targetField, nullableResult = false)
                        ?.let { builder ->

                            val relationType = targetField
                                .getDirectiveArgument<String>(DirectiveConstants.RELATION, DirectiveConstants.RELATION_NAME, null)
                                ?.let { buildingEnv.getTypeForRelation(it) }

                            relationType
                                ?.fieldDefinitions
                                ?.filter { it.type.isScalar() && !it.isID() }
                                ?.forEach { builder.argument(input(it.name, it.type as GraphQLScalarType)) }

                            buildingEnv.addOperation(MUTATION, builder.build())
                        }

                }
        }

        override fun createDataFetcher(
                sourceType: GraphQLFieldsContainer,
                relation: RelationshipInfo,
                startIdField: RelationshipInfo.RelatedField,
                endIdField: RelationshipInfo.RelatedField,
                fieldDefinition: GraphQLFieldDefinition
        ): DataFetcher<Cypher>? {
            return CreateRelationHandler(sourceType, relation, startIdField, endIdField, fieldDefinition)
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
                " MERGE (${relation.startField})-[:${relation.relType.quote()}${properties.query}]->(${relation.endField})" +
                " WITH DISTINCT ${relation.startField} AS $variable" +
                " RETURN ${mapProjection.query} AS $variable",
                startSelect.params + endSelect.params + properties.params)
    }
}
