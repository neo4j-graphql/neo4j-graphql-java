package org.neo4j.graphql

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import java.util.*

object NoOpCoercing : Coercing<Any, Any> {

    override fun parseLiteral(
        input: Value<*>,
        variables: CoercedVariables,
        graphQLContext: GraphQLContext,
        locale: Locale
    ): Any = input.toJavaValue()
        ?: throw CoercingParseLiteralException("literal should not be null")

    override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): Any =
        dataFetcherResult

    override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): Any = input.toJavaValue()
        ?: throw CoercingParseValueException("literal should not be null")
}
