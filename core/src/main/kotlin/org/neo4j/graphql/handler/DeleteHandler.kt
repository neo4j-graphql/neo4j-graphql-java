package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.language.ImplementingTypeDefinition
import graphql.language.TypeName
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLType
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*

/**
 * This class handles all the logic related to the deletion of nodes.
 * This includes the augmentation of the delete&lt;Node&gt;-mutator and the related cypher generation
 */
class DeleteHandler private constructor(schemaConfig: SchemaConfig) : BaseDataFetcherForContainer(schemaConfig) {

    private lateinit var idField: GraphQLFieldDefinition
    private var isRelation: Boolean = false

    class Factory(schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        override fun augmentType(type: ImplementingTypeDefinition<*>) {
            if (!canHandle(type)) {
                return
            }
            val idField = type.getIdField() ?: return

            val fieldDefinition = buildFieldDefinition("delete", type, listOf(idField), nullableResult = true)
                .description("Deletes ${type.name} and returns the type itself".asDescription())
                .type(TypeName(type.name))
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
            type.getIdField() ?: return null
            return when (fieldDefinition.name) {
                "delete${type.name}" -> DeleteHandler(schemaConfig)
                else -> null
            }
        }

        private fun canHandle(type: ImplementingTypeDefinition<*>): Boolean {
            val typeName = type.name
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(typeName) || isRootType(type)) {
                return false
            }
            return type.getIdField() != null
        }
    }

    override fun initDataFetcher(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType) {
        super.initDataFetcher(fieldDefinition, parentType)
        idField = type.getIdField() ?: throw IllegalStateException("Cannot resolve id field for type ${type.name}")
        isRelation = type.isRelationType()
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
        val (mapProjection, subQueries) = projectFields(propertyContainer, type, env)

        val projection = propertyContainer.project(mapProjection).`as`(variable)
        return select
            .withSubQueries(subQueries)
            .with(deletedElement, projection)
            .detachDelete(deletedElement)
            .returning(projection.asName().`as`(field.aliasOrName()))
            .build()
    }

}
