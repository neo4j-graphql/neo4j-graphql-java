package org.neo4j.graphql

import graphql.execution.MergedField
import graphql.language.Document
import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.OperationDefinition
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

    @JvmOverloads
    @Throws(OptimizedQueryException::class)
    fun translate(query: String, params: Map<String, Any?> = emptyMap(), ctx: QueryContext = QueryContext()): List<Cypher> {
        val ast = parse(query) // todo preparsedDocumentProvider
        val fragments = ast.definitions.filterIsInstance<FragmentDefinition>().associateBy { it.name }
        return ast.definitions.filterIsInstance<OperationDefinition>()
            .filter { it.operation == QUERY || it.operation == MUTATION } // todo variableDefinitions, directives, name
            .flatMap { operationDefinition ->
                operationDefinition.selectionSet.selections
                    .filterIsInstance<Field>() // FragmentSpread, InlineFragment
                    .map { field ->
                        val cypher = toQuery(operationDefinition.operation, field, fragments, params, ctx)
                        val resolvedParams = cypher.params.mapValues { toBoltValue(it.value) }
                        cypher.with(resolvedParams)
                    }
            }
    }

    private fun toBoltValue(value: Any?) = when (value) {
        is BigInteger -> value.longValueExact()
        is BigDecimal -> value.toDouble()
        else -> value
    }

    private fun toQuery(op: OperationDefinition.Operation,
            field: Field,
            fragments: Map<String, FragmentDefinition?>,
            variables: Map<String, Any?>,
            ctx: QueryContext = QueryContext()
    ): Cypher {
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
            .parentType(operationObjectType)
            .graphQLSchema(schema)
            .fragmentsByName(fragments)
            .context(ctx)
            .localContext(ctx)
            .fieldDefinition(fieldDefinition)
            .variables(variables)
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
