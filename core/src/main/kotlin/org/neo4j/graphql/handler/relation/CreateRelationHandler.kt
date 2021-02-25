package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
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
                                ?.forEach { builder.argument(input(it.name, it.type)) }

                            buildingEnv.addMutationField(builder.build())
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
            env: DataFetchingEnvironment
    ): Cypher {

        val properties = properties(variable, field.arguments)
        val mapProjection = projectFields(variable, field, type, env, null)

        val arguments = field.arguments.map { it.name to it }.toMap()
        val startSelect = getRelationSelect(true, arguments)
        val endSelect = getRelationSelect(false, arguments)

        return Cypher("MATCH ${startSelect.query}" +
                " MATCH ${endSelect.query}" +
                " MERGE (${relation.startField})${relation.arrows.first}-[:${relation.relType.quote()}${properties.query}]-${relation.arrows.second}(${relation.endField})" +
                " WITH DISTINCT ${relation.startField} AS $variable" +
                " RETURN ${mapProjection.query} AS ${field.aliasOrName()}",
                startSelect.params + endSelect.params + properties.params)
    }
}
