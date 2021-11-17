package org.neo4j.graphql.handler

import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLType
import graphql.schema.idl.TypeDefinitionRegistry
import org.atteo.evo.inflector.English
import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.Relationship
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingMatchAndUpdate
import org.neo4j.graphql.*

/**
 * This class handles all the logic related to the updating of nodes.
 * This includes the augmentation of the update&lt;Node&gt; and merge&lt;Node&gt;-mutator and the related cypher generation
 */
class BatchUpdateHandler private constructor(schemaConfig: SchemaConfig) : BaseDataFetcherForContainer(schemaConfig) {

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
            val inputName = type.name + "UpdateInput"
            val plural = English.plural(type.name).capitalize()
            addInputType(inputName, getInputValueDefinitions(relevantFields, false, { true }))
            val response = addMutationResponse("Update", type).name
            val filter = addFilterType(type)
            val updateField = FieldDefinition.newFieldDefinition()
                .name("${"update"}${plural}")
                .inputValueDefinitions(listOf(
                        input(if (schemaConfig.useWhereFilter) WHERE else FILTER, TypeName(filter)),
                        input("update", NonNullType(TypeName(inputName)))
                ))
                .type(NonNullType(TypeName(response)))
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
            if (fieldDefinition.name == "update${English.plural(type.name)}") {
                return BatchUpdateHandler(schemaConfig)
            }
            return null
        }

        private fun canHandle(type: ImplementingTypeDefinition<*>): Boolean {
            val typeName = type.name
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(typeName) || isRootType(type)) {
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
        val merge = false // TODO
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
        val update: OngoingMatchAndUpdate = select
            .mutate(propertyContainer, org.neo4j.cypherdsl.core.Cypher.mapOf(*properties))

        return update
            .with(propertyContainer)
            .returning(propertyContainer.project(mapProjection).`as`(field.aliasOrName()))
            .build()
    }
}
