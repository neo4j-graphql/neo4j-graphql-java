package org.neo4j.graphql

import graphql.execution.MergedField
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

    /**
     * @param name the name used by the graphQL query
     * @param propertyName the name used in neo4j
     * @param value the value to set the neo4j property to
     */
    data class CypherArgument(
            val name: String,
            val propertyName: String,
            val value: Any?,
            val converter: Neo4jConverter = Neo4jConverter(),
            val cypherParam: String = name) {
        fun toCypherString(variable: String, asJson: Boolean = true): String {
            val separator = when (asJson) {
                true -> ": "
                false -> " = "
            }
            return "$propertyName$separator${converter.parseValue(paramName(variable, name, value))}"
        }
    }

    @JvmOverloads
    @Throws(OptimizedQueryException::class)
    fun translate(query: String, params: Map<String, Any?> = emptyMap(), ctx: QueryContext = QueryContext()): List<Cypher> {
        val ast = parse(query) // todo preparsedDocumentProvider
        val fragments = ast.definitions.filterIsInstance<FragmentDefinition>().map { it.name to it }.toMap()
        return ast.definitions.filterIsInstance<OperationDefinition>()
            .filter { it.operation == QUERY || it.operation == MUTATION } // todo variableDefinitions, directives, name
            .flatMap { operationDefinition ->
                operationDefinition.selectionSet.selections
                    .filterIsInstance<Field>() // FragmentSpread, InlineFragment
                    .map { field ->
                        val cypher = toQuery(operationDefinition.operation, field, fragments, ctx)
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

    private fun toQuery(op: OperationDefinition.Operation, field: Field, fragments: Map<String, FragmentDefinition?>, ctx: QueryContext = QueryContext()): Cypher {
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
            .mergedField(MergedField.newMergedField(field).build())
            .graphQLSchema(schema)
            .fragmentsByName(fragments)
            .context(ctx)
            .localContext(ctx)
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