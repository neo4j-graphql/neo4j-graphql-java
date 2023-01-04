package org.neo4j.graphql.scalars

import graphql.Assert
import graphql.language.*
import graphql.schema.*
import org.neo4j.graphql.Constants
import java.time.Duration
import java.time.Period
import java.time.temporal.TemporalAmount

object DurationScalar {

    val INSTANCE: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name(Constants.DURATION)
        .coercing(object : Coercing<TemporalAmount, Any> {
            @Throws(CoercingSerializeException::class)
            override fun serialize(input: Any): Any {
                return input.toString()
            }

            @Throws(CoercingParseValueException::class)
            override fun parseValue(input: Any): TemporalAmount {
                return parseLiteral(input)
            }

            @Throws(CoercingParseLiteralException::class)
            override fun parseLiteral(input: Any): TemporalAmount {
                return when (input) {
                    is StringValue -> runCatching { Duration.parse(input.value) }.getOrElse { Period.parse(input.value) }
                    else -> Assert.assertShouldNeverHappen("Only string or number is expected")
                }
            }
        })
        .build()

}
