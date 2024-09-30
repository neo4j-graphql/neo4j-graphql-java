package org.neo4j.graphql.scalars

import graphql.Assert
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.*
import graphql.schema.*
import org.neo4j.graphql.Constants
import java.util.*

object BigIntScalar {

    val INSTANCE: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name(Constants.BIG_INT)
        .coercing(object : Coercing<Number, String> {

            @Throws(CoercingSerializeException::class)
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String {
                return dataFetcherResult.toString()
            }

            @Throws(CoercingParseValueException::class)
            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): Number? {
                return when (input) {
                    is StringValue -> input.value.toLong()
                    is FloatValue -> input.value
                    is IntValue -> input.value
                    is String -> input.toLong()
                    is Float, is Int, is Long -> input as Number
                    else -> Assert.assertShouldNeverHappen("Only string or number is expected")
                }
            }

            @Throws(CoercingParseLiteralException::class)
            override fun parseLiteral(
                input: Value<*>,
                variables: CoercedVariables,
                graphQLContext: GraphQLContext,
                locale: Locale
            ): Number? {
                return parseValue(input, graphQLContext, locale)
            }
        })
        .build()

}
