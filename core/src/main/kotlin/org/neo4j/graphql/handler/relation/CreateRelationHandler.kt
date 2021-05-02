package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingUpdate
import org.neo4j.graphql.*

/**
 * This class handles all the logic related to the creation of relations starting from an existing node.
 * This includes the augmentation of the add&lt;Edge&gt;-mutator and the related cypher generation
 */
class CreateRelationHandler private constructor(
        type: GraphQLFieldsContainer,
        relation: RelationshipInfo,
        startId: RelationshipInfo.RelatedField,
        endId: RelationshipInfo.RelatedField,
        fieldDefinition: GraphQLFieldDefinition,
        schemaConfig: SchemaConfig
) : BaseRelationHandler(type, relation, startId, endId, fieldDefinition, schemaConfig) {

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
        ): DataFetcher<Cypher> {
            return CreateRelationHandler(sourceType, relation, startIdField, endIdField, fieldDefinition, schemaConfig)
        }

    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {

        val properties = properties(variable, field.arguments)

        val arguments = field.arguments.map { it.name to it }.toMap()
        val (startNode, startWhere) = getRelationSelect(true, arguments)
        val (endNode, endWhere) = getRelationSelect(false, arguments)

        val mapProjection = projectFields(startNode, field, type, env)

        val update: OngoingUpdate = org.neo4j.cypherdsl.core.Cypher.match(startNode).where(startWhere)
            .match(endNode).where(endWhere)
            .merge(relation.createRelation(startNode, endNode).withProperties(*properties))
        val withAlias = startNode.`as`(variable)
        return update
            .withDistinct(withAlias)
            .returning(withAlias.asName().project(mapProjection).`as`(field.aliasOrName()))
            .build()
    }
}
