package org.neo4j.graphql

import graphql.ExceptionWhileDataFetching
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.InvalidSyntaxError
import graphql.schema.GraphQLSchema
import graphql.validation.ValidationError

class Translator(val schema: GraphQLSchema) {

    class CypherHolder(var cypher: Cypher?)

    private val gql: GraphQL = GraphQL.newGraphQL(schema).build()

    @JvmOverloads
    @Throws(OptimizedQueryException::class)
    fun translate(query: String, params: Map<String, Any?> = emptyMap(), ctx: QueryContext = QueryContext()): List<Cypher> {
        val cypherHolder = CypherHolder(null)
        val executionInput = ExecutionInput.newExecutionInput()
            .query(query)
            .variables(params)
            .context(ctx)
            .localContext(cypherHolder)
            .build()
        val result = gql.execute(executionInput)
        result.errors?.forEach {
            when (it) {
                is ExceptionWhileDataFetching -> throw it.exception
                is ValidationError -> throw InvalidQueryException(it)
                is InvalidSyntaxError -> throw InvalidQueryException(it)
            }
        }

        return listOf(requireNotNull(cypherHolder.cypher))
    }
}
