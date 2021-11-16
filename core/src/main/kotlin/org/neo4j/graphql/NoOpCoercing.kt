package org.neo4j.graphql

import graphql.schema.Coercing

object NoOpCoercing : Coercing<Any, Any> {
    override fun parseLiteral(input: Any?) = input?.toJavaValue()

    override fun serialize(dataFetcherResult: Any?) = dataFetcherResult

    override fun parseValue(input: Any) = input.toJavaValue()
}
