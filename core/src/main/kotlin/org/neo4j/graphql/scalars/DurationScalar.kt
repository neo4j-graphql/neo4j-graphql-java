package org.neo4j.graphql.scalars

import graphql.Assert
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.*
import graphql.schema.*
import org.neo4j.graphql.Constants
import java.time.Duration
import java.time.Period
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAmount
import java.util.*

object DurationScalar {

    val INSTANCE: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name(Constants.DURATION)
        .coercing(object : Coercing<TemporalAmount, String> {

            private fun  parse(value: String): TemporalAmount {
                try {
                    return Duration.parse(value)
                } catch (e: DateTimeParseException){
                    return Period.parse(value)
                }
            }

            @Throws(CoercingSerializeException::class)
            override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String {
                return dataFetcherResult.toString()
            }

            @Throws(CoercingParseValueException::class)
            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): TemporalAmount? {
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
            ): TemporalAmount? {
                return parseValue(input, graphQLContext, locale)
            }
        })
        .build()

}
