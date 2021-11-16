package org.neo4j.graphql.handler.relation

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.language.ImplementingTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.cypherdsl.core.Cypher.name
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*

/**
 * This class handles all the logic related to the creation of relations.
 * This includes the augmentation of the create&lt;Edge&gt;-mutator and the related cypher generation
 */
class CreateRelationTypeHandler private constructor(schemaConfig: SchemaConfig) : BaseRelationHandler("create", schemaConfig) {

    class Factory(schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        override fun augmentType(type: ImplementingTypeDefinition<*>) {
            if (!canHandle(type)) {
                return
            }
            val relation = type.relationship() ?: return
            val startIdField = getRelatedIdField(relation, relation.startField)
            val endIdField = getRelatedIdField(relation, relation.endField)
            if (startIdField == null || endIdField == null) {
                return
            }

            val createArgs = getRelevantFields(type)
                .filter { !it.isNativeId() }
                .filter { it.name != startIdField.argumentName }
                .filter { it.name != endIdField.argumentName }

            val builder =
                    buildFieldDefinition("create", type, createArgs, nullableResult = false)
                        .inputValueDefinition(input(startIdField.argumentName, startIdField.field.type))
                        .inputValueDefinition(input(endIdField.argumentName, endIdField.field.type))

            addMutationField(builder.build())
        }

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: FieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.MUTATION) {
                return null
            }
            if (fieldDefinition.cypherDirective() != null) {
                return null
            }
            val type = fieldDefinition.type.inner().resolve() as? ImplementingTypeDefinition<*> ?: return null
            if (!canHandle(type)) {
                return null
            }
            if (fieldDefinition.name != "create${type.name}") {
                return null
            }
            return CreateRelationTypeHandler(schemaConfig)
        }

        private fun getRelevantFields(type: ImplementingTypeDefinition<*>): List<FieldDefinition> {
            return type
                .getScalarFields()
                .filter { !it.isNativeId() }
        }

        private fun canHandle(type: ImplementingTypeDefinition<*>): Boolean {
            val typeName = type.name
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(typeName)) {
                return false
            }
            if (type is InterfaceTypeDefinition) {
                return false
            }
            val relation = type.relationship() ?: return false
            val startIdField = getRelatedIdField(relation, relation.startField)
            val endIdField = getRelatedIdField(relation, relation.endField)
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


        data class RelatedField(
                val argumentName: String,
                val field: FieldDefinition,
        )

        private fun getRelatedIdField(info: RelationshipInfo<ImplementingTypeDefinition<*>>, relFieldName: String?): RelatedField? {
            if (relFieldName == null) return null
            val relFieldDefinition = info.type.getFieldDefinition(relFieldName)
                    ?: throw IllegalArgumentException("field $relFieldName does not exists on ${info.typeName}")

            val relType = relFieldDefinition.type.inner().resolve() as? ImplementingTypeDefinition<*>
                    ?: throw IllegalArgumentException("type ${relFieldDefinition.type.name()} not found")
            return relType.fieldDefinitions
                .filterNot { it.isIgnored() }
                .filter { it.type.inner().isID() }
                .map { RelatedField(normalizeFieldName(relFieldName, it.name), it) }
                .firstOrNull()
        }

    }

    private fun getRelatedIdField(info: RelationshipInfo<GraphQLFieldsContainer>, relFieldName: String): RelatedField {
        val relFieldDefinition = info.type.getRelevantFieldDefinition(relFieldName)
                ?: throw IllegalArgumentException("field $relFieldName does not exists on ${info.typeName}")

        val relType = relFieldDefinition.type.inner() as? GraphQLImplementingType
                ?: throw IllegalArgumentException("type ${relFieldDefinition.type.name()} not found")
        return relType.getRelevantFieldDefinitions().filter { it.isID() }
            .map { RelatedField(normalizeFieldName(relFieldName, it.name), it, relType) }
            .firstOrNull()
                ?: throw IllegalStateException("Cannot find id field for type ${info.typeName}")
    }

    override fun initRelation(fieldDefinition: GraphQLFieldDefinition) {
        relation = type.relationship()
                ?: throw IllegalStateException("Cannot resolve relationship for type ${type.name}")
        startId = getRelatedIdField(relation, relation.startField)
        endId = getRelatedIdField(relation, relation.endField)
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val properties = properties(variable, env.arguments)

        val arguments = field.arguments.associateBy { it.name }
        val (startNode, startWhere) = getRelationSelect(true, arguments)
        val (endNode, endWhere) = getRelationSelect(false, arguments)
        val relName = name(variable)
        val (mapProjection, subQueries) = projectFields(startNode, type, env, relName)

        return org.neo4j.cypherdsl.core.Cypher.match(startNode).where(startWhere)
            .match(endNode).where(endWhere)
            .create(relation.createRelation(startNode, endNode).withProperties(*properties).named(relName))
            .with(relName)
            .withSubQueries(subQueries)
            .returning(relName.project(mapProjection).`as`(field.aliasOrName()))
            .build()
    }

    companion object {
        private fun normalizeFieldName(relFieldName: String?, name: String): String {
            // TODO b/c we need to stay backwards compatible this is not camel-case but with underscore
            //val filedName = normalizeName(relFieldName, name)
            return "${relFieldName}_${name}"
        }
    }
}
