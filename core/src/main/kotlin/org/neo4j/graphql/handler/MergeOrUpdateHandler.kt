package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.language.FieldDefinition
import graphql.language.ImplementingTypeDefinition
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
 * This class handles all the logic related to the updating of nodes.
 * This includes the augmentation of the update&lt;Node&gt; and merge&lt;Node&gt;-mutator and the related cypher generation
 */
class MergeOrUpdateHandler private constructor(private val merge: Boolean, schemaConfig: SchemaConfig) : BaseDataFetcherForContainer(schemaConfig) {

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

            val relevantFields = type.getScalarFields()
            val idField = type.getIdField()
                    ?: throw IllegalStateException("Cannot resolve id field for type ${type.name}")

            val mergeField = buildFieldDefinition("merge", type, relevantFields, nullableResult = false, forceOptionalProvider = { it != idField })
                .build()
            addMutationField(mergeField)

            val updateField = buildFieldDefinition("update", type, relevantFields, nullableResult = true, forceOptionalProvider = { it != idField })
                .build()
            addMutationField(updateField)
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
                "merge${type.name}" -> MergeOrUpdateHandler(true, schemaConfig)
                "update${type.name}" -> MergeOrUpdateHandler(false, schemaConfig)
                else -> null
            }
        }

        private fun canHandle(type: ImplementingTypeDefinition<*>): Boolean {
            val typeName = type.name
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(typeName) || isRootType(type)) {
                return false
            }
            if (type.getIdField() == null) {
                return false
            }
            if (type.getScalarFields().none { !it.type.inner().isID() }) {
                // nothing to update (except ID)
                return false
            }
            return true
        }
    }

    override fun initDataFetcher(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType) {
        super.initDataFetcher(fieldDefinition, parentType)

        idField = type.getIdField() ?: throw IllegalStateException("Cannot resolve id field for type ${type.name}")
        isRelation = type.isRelationType()

        defaultFields.clear() // for merge or updates we do not reset to defaults
        propertyFields.remove(idField.name) // id should not be updated

    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val idArg = field.arguments.first { it.name == idField.name }

        val (propertyContainer, where) = getSelectQuery(variable, type.label(), idArg, idField, isRelation)

        val select = if (isRelation) {
            val rel = propertyContainer as? Relationship
                    ?: throw IllegalStateException("Expect a Relationship but got ${propertyContainer.javaClass.name}")
            if (merge && !idField.isNativeId()) {
                org.neo4j.cypherdsl.core.Cypher.merge(rel)
                // where is skipped since it does not make sense on merge
            } else {
                org.neo4j.cypherdsl.core.Cypher.match(rel).where(where)
            }
        } else {
            val node = propertyContainer as? Node
                    ?: throw IllegalStateException("Expect a Node but got ${propertyContainer.javaClass.name}")
            if (merge && !idField.isNativeId()) {
                org.neo4j.cypherdsl.core.Cypher.merge(node)
                // where is skipped since it does not make sense on merge
            } else {
                org.neo4j.cypherdsl.core.Cypher.match(node).where(where)
            }
        }
        val properties = properties(variable, env.arguments)
        val (mapProjection, subQueries) = projectFields(propertyContainer, type, env)

        return select
            .mutate(propertyContainer, org.neo4j.cypherdsl.core.Cypher.mapOf(*properties))
            .with(propertyContainer)
            .withSubQueries(subQueries)
            .returning(propertyContainer.project(mapProjection).`as`(field.aliasOrName()))
            .build()
    }
}
