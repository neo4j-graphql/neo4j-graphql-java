package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder
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
                        ?.let { builder -> buildingEnv.addMutationField(builder.build()) }
                }
        }

        override fun createDataFetcher(
                sourceType: GraphQLFieldsContainer,
                relation: RelationshipInfo,
                startIdField: RelationshipInfo.RelatedField,
                endIdField: RelationshipInfo.RelatedField,
                fieldDefinition: GraphQLFieldDefinition
        ): DataFetcher<Cypher> {
            return DeleteRelationHandler(sourceType, relation, startIdField, endIdField, fieldDefinition)
        }

    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val arguments = field.arguments.map { it.name to it }.toMap()
        val (startNode, startWhere) = getRelationSelect(true, arguments)
        val (endNode, endWhere) = getRelationSelect(false, arguments)
        val relName = org.neo4j.cypherdsl.core.Cypher.name("r")

        val update: StatementBuilder.OngoingUpdate = org.neo4j.cypherdsl.core.Cypher
            .match(startNode).where(startWhere)
            .match(endNode).where(endWhere)
            .match(relation.createRelation(startNode, endNode).named(relName))
            .delete(relName)

        val withAlias = startNode.`as`(variable)
        val mapProjection = projectFields(startNode, withAlias.asName(), field, type, env)
        return update
            .withDistinct(withAlias)
            .returning(withAlias.asName().project(mapProjection).`as`(field.aliasOrName()))
            .build()
    }

}
