package org.neo4j.graphql.handler

import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.atteo.evo.inflector.English
import org.neo4j.cypherdsl.core.Cypher.mapOf
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.handler.filter.OptimizedFilterHandler

/**
 * This class handles all the logic related to the updating of nodes.
 * This includes the augmentation of the update&lt;Node&gt; and merge&lt;Node&gt;-mutator and the related cypher generation
 */
class BatchUpdateHandler private constructor(schemaConfig: SchemaConfig, typeName: String, private val nestedResultField: String?) : BaseDataFetcherForContainerBatch(schemaConfig, typeName), HasNestedStatistics {
    companion object {
        const val UPDATE_INFO = "UpdateInfo"
        const val UPDATE_INPUT_FIELD = "update"
    }

    private var isRelation: Boolean = false
    lateinit var updateProperties: InputProperties


    class Factory(schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        companion object {
            const val METHOD_NAME_PREFIX = "update"
        }

        override fun augmentType(type: ImplementingTypeDefinition<*>) {
            if (!canHandle(type)) {
                return
            }
            val relevantFields = type.getScalarFields()
            val inputName = type.name + "UpdateInput"
            val plural = English.plural(type.name).capitalize()
            addInputType(inputName, getInputValueDefinitions(relevantFields, addFieldOperations = false, forceOptionalProvider = { true }))

            val response = if (schemaConfig.shouldWrapMutationResults) {
                val statistics = if (schemaConfig.enableStatistics) NonNullType(TypeName(UPDATE_INFO)) else null
                NonNullType(TypeName(addMutationResponse("Update", type, statistics).name))
            } else {
                NonNullType(ListType(NonNullType(TypeName(type.name))))
            }

            val filter = addFilterType(type)
            val updateField = FieldDefinition.newFieldDefinition()
                .name("${METHOD_NAME_PREFIX}${plural}")
                .inputValueDefinitions(listOf(
                        input(if (schemaConfig.useWhereFilter) WHERE else FILTER, TypeName(filter)),
                        input(UPDATE_INPUT_FIELD, NonNullType(TypeName(inputName)))
                ))
                .type(response)
                .build()
            addMutationField(updateField)
        }

        override fun createDataFetcher(operationType: OperationType, fieldDefinition: FieldDefinition): DataFetcher<Cypher>? {
            if (operationType != OperationType.MUTATION) return null
            if (fieldDefinition.cypherDirective() != null) return null

            val (type, nestedResultField) = getTypeAndOptionalWrapperField(fieldDefinition, METHOD_NAME_PREFIX)
                    ?: return null


            if (!canHandle(type)) {
                return null
            }
            if (fieldDefinition.name == METHOD_NAME_PREFIX + English.plural(type.name)) {
                return BatchUpdateHandler(schemaConfig, type.name, nestedResultField)
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

    override fun initDataFetcher(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType, graphQLSchema: GraphQLSchema) {
        super.initDataFetcher(fieldDefinition, parentType, graphQLSchema)
        isRelation = type.isRelationType()

        val update = fieldDefinition.getArgument(UPDATE_INPUT_FIELD)
                ?: throw IllegalStateException("${parentType.name()}.${fieldDefinition.name} expected to have an argument named ${UPDATE_INPUT_FIELD}")
        val inputType = update.type.inner() as GraphQLInputObjectType
        updateProperties = InputProperties.fromInputType(schemaConfig, type, inputType, fallbackToDefaults = false)
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val fieldDefinition = env.fieldDefinition
        val (propertyContainer, match) = when {
            isRelation -> org.neo4j.cypherdsl.core.Cypher.anyNode().relationshipTo(org.neo4j.cypherdsl.core.Cypher.anyNode(), type.label()).named("this")
                .let { rel -> rel to org.neo4j.cypherdsl.core.Cypher.match(rel) }
            else -> org.neo4j.cypherdsl.core.Cypher.node(type.label()).named("this")
                .let { node -> node to org.neo4j.cypherdsl.core.Cypher.match(node) }
        }

        val ongoingReading = if ((env.getContext() as? QueryContext)?.optimizedQuery?.contains(QueryContext.OptimizationStrategy.FILTER_AS_MATCH) == true) {

            OptimizedFilterHandler(type, schemaConfig).generateFilterQuery(variable, fieldDefinition, env.arguments, match, propertyContainer, env.variables)

        } else {

            val where = where(propertyContainer, fieldDefinition, type, env.arguments, env.variables)
            match.where(where)
        }

        @Suppress("UNCHECKED_CAST") val inputData = env.arguments[UPDATE_INPUT_FIELD] as Map<String, Any>
        val properties = updateProperties.properties(variable, inputData)
        val update = ongoingReading
            .mutate(propertyContainer, mapOf(*properties))
            .with(propertyContainer)

        val selectionSet = if (schemaConfig.shouldWrapMutationResults) {
            env.selectionSet.getFields(nestedResultField).first().selectionSet
        } else {
            env.selectionSet
        }
        val (projectionEntries, subQueries) = projectFields(propertyContainer, type, env, selectionSet = selectionSet)
        return update
            .withSubQueries(subQueries)
            .returning(propertyContainer.project(projectionEntries).`as`(variable))
            .build()
    }

    override fun getDataField(): String = nestedResultField
            ?: throw IllegalArgumentException("No nested result field defined, enable `SchemaConfig::wrapMutationResults` to wrap the result field")

    override fun getStatisticsField(): String = AugmentationHandler.INFO_FIELD
}
