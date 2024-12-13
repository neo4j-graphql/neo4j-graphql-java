package org.neo4j.graphql.scalars

import graphql.Assert
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.*
import graphql.schema.*
import org.neo4j.graphql.Constants
import org.threeten.extra.PeriodDuration
import java.util.*

object DurationScalar {

    val INSTANCE: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name(Constants.DURATION)
        .coercing(object : Coercing<PeriodDuration, String> {

            private fun parse(value: String): PeriodDuration {
                return PeriodDuration.parse(value)
            }

            @Throws(CoercingSerializeException::class)
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String {
                return dataFetcherResult.toString()
            }

            @Throws(CoercingParseValueException::class)
            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): PeriodDuration? {
                return when (input) {
                    is StringValue -> parse(input.value)
                    is String -> parse(input)
                    else -> Assert.assertShouldNeverHappen("Only string is expected")
                }
            }

            @Throws(CoercingParseLiteralException::class)
            override fun parseLiteral(
                input: Value<*>,
                variables: CoercedVariables,
                graphQLContext: GraphQLContext,
                locale: Locale
            ): PeriodDuration? {
                return parseValue(input, graphQLContext, locale)
            }
        })
        .build()

}
