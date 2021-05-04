package org.neo4j.graphql.handler

import graphql.language.Field
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer
import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingMatchAndUpdate
import org.neo4j.graphql.*

/**
 * This class handles all the logic related to the updating of nodes.
 * This includes the augmentation of the update&lt;Node&gt; and merge&lt;Node&gt;-mutator and the related cypher generation
 */
class MergeOrUpdateHandler private constructor(
        type: GraphQLFieldsContainer,
        private val merge: Boolean,
        private val idField: GraphQLFieldDefinition,
        fieldDefinition: GraphQLFieldDefinition,
        schemaConfig: SchemaConfig,
        private val isRelation: Boolean = type.isRelationType()
) : BaseDataFetcherForContainer(type, fieldDefinition, schemaConfig) {

    class Factory(schemaConfig: SchemaConfig) : AugmentationHandler(schemaConfig) {
        override fun augmentType(type: GraphQLFieldsContainer, buildingEnv: BuildingEnv) {
            if (!canHandle(type)) {
                return
            }

            val relevantFields = type.relevantFields()
            val mergeField = buildingEnv
                .buildFieldDefinition("merge", type, relevantFields, nullableResult = false)
                .build()
            buildingEnv.addMutationField(mergeField)

            val updateField = buildingEnv
                .buildFieldDefinition("update", type, relevantFields, nullableResult = true)
                .build()
            buildingEnv.addMutationField(updateField)
        }

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: GraphQLFieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.MUTATION) {
                return null
            }
            if (fieldDefinition.cypherDirective() != null) {
                return null
            }
            val type = fieldDefinition.type.inner() as? GraphQLFieldsContainer
                    ?: return null
            if (!canHandle(type)) {
                return null
            }
            val idField = type.getIdField() ?: return null
            return when (fieldDefinition.name) {
                "merge${type.name}" -> MergeOrUpdateHandler(type, true, idField, fieldDefinition, schemaConfig)
                "update${type.name}" -> MergeOrUpdateHandler(type, false, idField, fieldDefinition, schemaConfig)
                else -> null
            }
        }

        private fun canHandle(type: GraphQLFieldsContainer): Boolean {
            val typeName = type.name
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(typeName)) {
                return false
            }
            if (type.getIdField() == null) {
                return false
            }
            if (type.relevantFields().none { !it.isID() }) {
                // nothing to update (except ID)
                return false
            }
            return true
        }
    }

    init {
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
        val properties = properties(variable, field.arguments)
        val mapProjection = projectFields(propertyContainer, field, type, env)
        val update: OngoingMatchAndUpdate = select
            .mutate(propertyContainer, org.neo4j.cypherdsl.core.Cypher.mapOf(*properties))

        return update
            .with(propertyContainer)
            .returning(propertyContainer.project(mapProjection).`as`(field.aliasOrName()))
            .build()
    }
}
