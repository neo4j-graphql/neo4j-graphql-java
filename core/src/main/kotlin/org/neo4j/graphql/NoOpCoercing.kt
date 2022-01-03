package org.neo4j.graphql

import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException

object NoOpCoercing : Coercing<Any, Any> {

    override fun parseLiteral(input: Any) = input.toJavaValue()
            ?: throw CoercingParseLiteralException("literal should not be null")

    override fun serialize(dataFetcherResult: Any) = dataFetcherResult

    override fun parseValue(input: Any) = input.toJavaValue()
            ?: throw CoercingParseValueException("literal should not be null")
}
