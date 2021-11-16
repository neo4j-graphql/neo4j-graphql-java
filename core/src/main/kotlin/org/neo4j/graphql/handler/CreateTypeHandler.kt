package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.language.ImplementingTypeDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLObjectType
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*

/**
 * This class handles all the logic related to the creation of nodes.
 * This includes the augmentation of the create&lt;Node&gt;-mutator and the related cypher generation
 */
class CreateTypeHandler private constructor(schemaConfig: SchemaConfig) : BaseDataFetcherForContainer(schemaConfig) {

    class Factory(schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        override fun augmentType(type: ImplementingTypeDefinition<*>) {
            if (!canHandle(type)) {
                return
            }
            val relevantFields = getRelevantFields(type)
            val fieldDefinition = buildFieldDefinition("create", type, relevantFields, nullableResult = false)
                .build()

            addMutationField(fieldDefinition)
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
            return when (fieldDefinition.name) {
                "create${type.name}" -> CreateTypeHandler(schemaConfig)
                else -> null
            }
        }

        private fun getRelevantFields(type: ImplementingTypeDefinition<*>): List<FieldDefinition> {
            return type
                .getScalarFields()
                .filter { !it.isNativeId() }
        }

        private fun canHandle(type: ImplementingTypeDefinition<*>): Boolean {
            val typeName = type.name
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(typeName) || isRootType(type)) {
                return false
            }
            if (type is InterfaceTypeDefinition) {
                return false
            }
            if (type.relationship() != null) {
                // relations are handled by the CreateRelationTypeHandler
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

        val additionalTypes = (type as? GraphQLObjectType)?.interfaces?.map { it.name } ?: emptyList()
        val node = org.neo4j.cypherdsl.core.Cypher.node(type.name, *additionalTypes.toTypedArray()).named(variable)

        val properties = properties(variable, env.arguments)
        val (mapProjection, subQueries) = projectFields(node, type, env)

        return org.neo4j.cypherdsl.core.Cypher.create(node.withProperties(*properties))
            .with(node)
            .withSubQueries(subQueries)
            .returning(node.project(mapProjection).`as`(field.aliasOrName()))
            .build()
    }

}
