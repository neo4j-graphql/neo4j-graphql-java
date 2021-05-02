package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.schema.*
import org.neo4j.cypherdsl.core.Cypher.name
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder
import org.neo4j.graphql.*

/**
 * This class handles all the logic related to the creation of relations.
 * This includes the augmentation of the create&lt;Edge&gt;-mutator and the related cypher generation
 */
class CreateRelationTypeHandler private constructor(
        type: GraphQLFieldsContainer,
        relation: RelationshipInfo,
        startId: RelationshipInfo.RelatedField,
        endId: RelationshipInfo.RelatedField,
        fieldDefinition: GraphQLFieldDefinition,
        schemaConfig: SchemaConfig
) : BaseRelationHandler(type, relation, startId, endId, fieldDefinition, schemaConfig) {

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

            buildingEnv.addMutationField(builder.build())
        }

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.MUTATION) {
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

            return CreateRelationTypeHandler(type, relation, startIdField, endIdField, fieldDefinition, schemaConfig)
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

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val properties = properties(variable, field.arguments)

        val arguments = field.arguments.map { it.name to it }.toMap()
        val (startNode, startWhere) = getRelationSelect(true, arguments)
        val (endNode, endWhere) = getRelationSelect(false, arguments)
        val relName = name(variable)
        val mapProjection = projectFields(startNode, relName, field, type, env)

        val update: StatementBuilder.OngoingUpdate = org.neo4j.cypherdsl.core.Cypher.match(startNode).where(startWhere)
            .match(endNode).where(endWhere)
            .create(relation.createRelation(startNode, endNode).withProperties(*properties).named(relName))
        return update
            .with(relName)
            .returning(relName.project(mapProjection).`as`(field.aliasOrName()))
            .build()
    }
}
