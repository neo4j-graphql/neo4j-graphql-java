package org.neo4j.graphql.handler

import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.atteo.evo.inflector.English
import org.neo4j.cypherdsl.core.Cypher.*
import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder
import org.neo4j.graphql.*

/**
 * This class handles all the logic related to the creation of multiple nodes.
 * This includes the augmentation of the create&lt;Node&gt;s-mutator and the related cypher generation
 */
class BatchCreateNodeHandler private constructor(schemaConfig: SchemaConfig, typeName: String, private val nestedResultField: String?) : BaseDataFetcherForContainerBatch(schemaConfig, typeName), HasNestedStatistics {
    companion object {
        const val CREATE_INFO = "CreateInfo"
        const val INPUT = "input"
    }

    private lateinit var inputProperties: InputProperties

    class Factory(schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        companion object {
            const val METHOD_NAME_PREFIX = "create"
        }

        override fun augmentType(type: ImplementingTypeDefinition<*>) {
            if (!canHandle(type)) {
                return
            }
            val relevantFields = getRelevantFields(type)
            val inputName = type.name + "CreateInput"
            val plural = English.plural(type.name).capitalize()
            addInputType(inputName, getInputValueDefinitions(relevantFields, addFieldOperations = false, forceOptionalProvider = { false }))

            val response = if (schemaConfig.shouldWrapMutationResults) {
                val statistics = if (schemaConfig.enableStatistics) NonNullType(TypeName(CREATE_INFO)) else null
                NonNullType(TypeName(addMutationResponse("Create", type, statistics).name))
            } else {
                NonNullType(ListType(NonNullType(TypeName(type.name))))
            }

            val createField = FieldDefinition.newFieldDefinition()
                .name("${METHOD_NAME_PREFIX}${plural}")
                .inputValueDefinitions(listOf(input(INPUT, NonNullType(ListType(NonNullType(TypeName(inputName)))))))
                .type(response)
                .build()

            addMutationField(createField)
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
                return BatchCreateNodeHandler(schemaConfig, type.name, nestedResultField)
            }
            return null
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
                return false
            }
            return true
        }

    }

    override fun initDataFetcher(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType, graphQLSchema: GraphQLSchema) {
        super.initDataFetcher(fieldDefinition, parentType, graphQLSchema)

        val input = fieldDefinition.getArgument(INPUT)
                ?: throw IllegalStateException("${parentType.name()}.${fieldDefinition.name} expected to have an argument named $INPUT")
        val inputType = input.type.inner() as GraphQLInputObjectType
        inputProperties = InputProperties.fromInputType(schemaConfig, type, inputType)
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {

        val additionalTypes = (type as? GraphQLObjectType)?.interfaces?.map { it.name } ?: emptyList()

        val input = env.arguments[INPUT]
        @Suppress("UNCHECKED_CAST") val inputs = (input as? List<Map<String, Any>>)
                ?: throw IllegalArgumentException("expected an input of type ${env.fieldDefinition.getArgument(INPUT).type} but got ${input?.javaClass?.name}")

        var queries: StatementBuilder.OngoingReadingWithoutWhere? = null
        val nodes = mutableListOf<Node>()
        inputs.forEachIndexed { index, value ->
            val properties = inputProperties.properties(variable + index, value)
            val node = node(type.name, *additionalTypes.toTypedArray()).named("this$index")
            val statement = create(node.withProperties(*properties)).returning(node).build()
            queries = queries?.call(statement) ?: call(statement)
            nodes += node
        }
        if (queries == null) {
            throw IllegalArgumentException("nothing to create for ${env.fieldDefinition}")
        }

        val (node, result) = if (nodes.size > 1) {
            val node = anyNode().named("this")
            node to queries!!
                .unwind(listOf(*nodes.map { it.requiredSymbolicName }.toTypedArray())).`as`(node.requiredSymbolicName)
        } else {
            nodes[0] to queries!!
        }

        val targetFieldSelection = env.selectionSet.getFields(nestedResultField).first().selectionSet
        val (projectionEntries, subQueries) = projectFields(node, type, env, selectionSet = targetFieldSelection)
        return result
            .withSubQueries(subQueries)
            .returning(node.project(projectionEntries).`as`(variable))
            .build()
    }

    override fun getDataField(): String = nestedResultField
            ?: throw IllegalArgumentException("No nested result field defined, enable `SchemaConfig::wrapMutationResults` to wrap the result field")

    override fun getStatisticsField(): String = AugmentationHandler.INFO_FIELD
}
