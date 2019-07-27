package org.neo4j.graphql

import graphql.language.*
import graphql.language.OperationDefinition.Operation.MUTATION
import graphql.language.OperationDefinition.Operation.QUERY
import graphql.parser.Parser
import graphql.schema.DataFetchingEnvironmentImpl.newDataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import org.antlr.v4.runtime.misc.ParseCancellationException
import java.math.BigDecimal
import java.math.BigInteger

class Translator(val schema: GraphQLSchema) {
    data class Context @JvmOverloads constructor(val topLevelWhere: Boolean = true,
            val fragments: Map<String, FragmentDefinition> = emptyMap(),
            val temporal: Boolean = false,
            val query: CRUDConfig = CRUDConfig(),
            val mutation: CRUDConfig = CRUDConfig(),
            val params: Map<String, Any?> = emptyMap(),
            var objectFilterProvider: ((variable: String, type: NodeFacade) -> Cypher?)? = null)

    data class CRUDConfig(val enabled: Boolean = true, val exclude: List<String> = emptyList())

    /**
     * @param name the name used by the graphQL query
     * @param propertyName the name used in neo4j
     * @param value the value to set the neo4j property to
     */
    data class CypherArgument(val name: String, val propertyName: String, val value: Any?)

    @JvmOverloads
    fun translate(query: String, params: Map<String, Any?> = emptyMap(), context: Context = Context()): List<Cypher> {
        val ast = parse(query) // todo preparsedDocumentProvider
        val ctx = context.copy(fragments = ast.definitions.filterIsInstance<FragmentDefinition>().map { it.name to it }.toMap())
        return ast.definitions.filterIsInstance<OperationDefinition>()
            .filter { it.operation == QUERY || it.operation == MUTATION } // todo variableDefinitions, directives, name
            .flatMap { operationDefinition ->
                operationDefinition.selectionSet.selections
                    .filterIsInstance<Field>() // FragmentSpread, InlineFragment
                    .map { field ->
                        val cypher = toQuery(operationDefinition.operation, field, ctx)
                        val resolvedParams = cypher.params.mapValues { toBoltValue(it.value, params) }
                        cypher.with(resolvedParams) // was cypher.with(params)
                    }
            }

    }

    private fun toBoltValue(value: Any?, params: Map<String, Any?>) = when (value) {
        is VariableReference -> params[value.name]
        is BigInteger -> value.longValueExact()
        is BigDecimal -> value.toDouble()
        else -> value
    }

    private fun toQuery(op: OperationDefinition.Operation, field: Field, ctx: Context = Context()): Cypher {
        val name = field.name
        val operationObjectType: GraphQLObjectType
        val fieldDefinition: GraphQLFieldDefinition
        when (op) {
            QUERY -> {
                operationObjectType = schema.queryType
                fieldDefinition = operationObjectType.getFieldDefinition(name)
                        ?: throw IllegalArgumentException("Unknown Query $name available queries: " + (operationObjectType.fieldDefinitions).joinToString { it.name })
            }
            MUTATION -> {
                operationObjectType = schema.mutationType
                fieldDefinition = operationObjectType.getFieldDefinition(name)
                        ?: throw IllegalArgumentException("Unknown Mutation $name available mutations: " + (operationObjectType.fieldDefinitions).joinToString { it.name })
            }
            else -> throw IllegalArgumentException("$op is not supported")
        }
        val dataFetcher = schema.codeRegistry.getDataFetcher(operationObjectType, fieldDefinition)
                ?: throw IllegalArgumentException("no data fetcher found for ${op.name.toLowerCase()} $name")


        return dataFetcher.get(newDataFetchingEnvironment()
            .source(field)
            .graphQLSchema(schema)
            .context(ctx)
            .fieldDefinition(fieldDefinition)
            .build()) as? Cypher
                ?: throw java.lang.IllegalStateException("not supported")
    }

    private fun parse(query: String): Document {
        try {
            val parser = Parser()
            return parser.parseDocument(query)
        } catch (e: ParseCancellationException) {
            // todo proper structured error
            throw e
        }
    }
}