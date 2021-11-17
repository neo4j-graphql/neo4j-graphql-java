package org.neo4j.graphql.handler

import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.atteo.evo.inflector.English
import org.neo4j.cypherdsl.core.Cypher.*
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.Node
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder
import org.neo4j.graphql.*

/**
 * This class handles all the logic related to the creation of multiple nodes.
 * This includes the augmentation of the create&lt;Node&gt;s-mutator and the related cypher generation
 */
class BatchCreateNodeHandler private constructor(schemaConfig: SchemaConfig) : BaseDataFetcherForContainerBatch(schemaConfig) {


    private lateinit var targetArrayField: String

    class Factory(schemaConfig: SchemaConfig,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
    ) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

        override fun augmentType(type: ImplementingTypeDefinition<*>) {
            if (!canHandle(type)) {
                return
            }
            val relevantFields = getRelevantFields(type)
            val inputName = type.name + "CreateInput"
            addInputType(inputName, getInputValueDefinitions(relevantFields, false, { false }))
            val plural = English.plural(type.name).capitalize()
            val response = addMutationResponse("Create", type).name
            val fieldDefinition = FieldDefinition.newFieldDefinition()
                .name("${"create"}${plural}")
                .inputValueDefinitions(listOf(input(INPUT, NonNullType(ListType(NonNullType(TypeName(inputName)))))))
                .type(NonNullType(TypeName(response)))
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
            // here we got the Create*MutationResponse with one array field of the required type
            val type = (fieldDefinition.type.inner().resolve() as ImplementingTypeDefinition)
                .fieldDefinitions.singleOrNull()
                ?.type?.inner()
                ?.resolve() as? ImplementingTypeDefinition<*>
                    ?: return null

            if (!canHandle(type)) {
                return null
            }
            if (fieldDefinition.name == "create${English.plural(type.name)}") return BatchCreateNodeHandler(schemaConfig)
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
                // TODO or should we support just creating empty nodes?
                return false
            }
            return true
        }

    }

    override fun initDataFetcher(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType) {
        targetArrayField = getResultArrayField(fieldDefinition).name
        super.initDataFetcher(fieldDefinition, parentType)
    }

    override fun getType(fieldDefinition: GraphQLFieldDefinition, parentType: GraphQLType): GraphQLFieldsContainer {
        // here we got the Create*MutationResponse with one array field of the required type
        return getResultArrayField(fieldDefinition)
            .type.inner() as? GraphQLFieldsContainer // the type of the array item
                ?: throw IllegalStateException("failed to extract correct type for ${parentType.name()}.${fieldDefinition.name}.")
    }

    private fun getResultArrayField(fieldDefinition: GraphQLFieldDefinition) =
            (fieldDefinition.type.inner() as GraphQLFieldsContainer) //Create*MutationResponse
                .fieldDefinitions.singleOrNull() // the one and only array field
                ?.takeIf { it.type.isList() }
                    ?: throw IllegalStateException("failed to extract array field for ${fieldDefinition.type.inner().name()}")

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {

        val additionalTypes = (type as? GraphQLObjectType)?.interfaces?.map { it.name } ?: emptyList()

        val input = env.arguments[INPUT]
        @Suppress("UNCHECKED_CAST") val inputs = (input as? List<Map<String, Any>>)
                ?: throw IllegalArgumentException("expected an input of type ${env.fieldDefinition.getArgument(INPUT).type} but got ${input?.javaClass?.name}")

        var queries: StatementBuilder.OngoingReadingWithoutWhere? = null
        val nodes = mutableListOf<Node>()
        inputs.forEachIndexed { index, value ->
            val properties = properties(variable, value)
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

        val targetFieldSelection = env.selectionSet.getFields(targetArrayField).first().selectionSet
        val (mapProjection, subQueries) = projectFields(node, type, env, selectionSet = targetFieldSelection)
        return result
            .returning(
                    mapOf(targetArrayField, Functions.collect(node.project(mapProjection)))
                        .`as`(field.aliasOrName()))
            .build()
    }

}
