package org.neo4j.graphql.scalars

import graphql.Assert
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.*
import graphql.schema.*
import org.neo4j.graphql.Constants
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.TemporalAccessor
import java.util.*

class TemporalScalar<T : TemporalAccessor> private constructor(val transformer: (t: TemporalAccessor) -> T) :
    Coercing<T, String> {

    @Throws(CoercingSerializeException::class)
    override fun serialize(dataFetcherResult: Any, graphQLContext: GraphQLContext, locale: Locale): String {
        return dataFetcherResult.toString()
    }

    @Throws(CoercingParseValueException::class)
    override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): T? {
        return when (input) {
            is StringValue -> transformer(parse(input.value))
            is String -> transformer(parse(input))
            else -> Assert.assertShouldNeverHappen("Only string is expected")
        }
    }

    @Throws(CoercingParseLiteralException::class)
    override fun parseLiteral(
        input: Value<*>,
        variables: CoercedVariables,
        graphQLContext: GraphQLContext,
        locale: Locale
    ): T? {
        return parseValue(input, graphQLContext, locale)
    }


    companion object {
        private val OFFSET_DATE_TIME_FORMATTER = DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendZoneOrOffsetId()
            .toFormatter()
        private val TIME_WITH_ZONE_FORMATTER = DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .appendZoneOrOffsetId()
            .toFormatter()
        private val CUSTOM_OFFSET_DATE_TIME_FORMATTER = DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendPattern("Z")
            .toFormatter()

        val DATE = createTemporalScalar(Constants.DATE) { LocalDate.from(it) }
        val TIME = createTemporalScalar(Constants.TIME) {
            val time = LocalTime.from(it)
            val offset = try {
                ZoneOffset.from(it)
            } catch (e: Exception) {
                ZoneOffset.UTC
            }
            OffsetTime.of(time, offset)
        }
        val LOCAL_TIME = createTemporalScalar(Constants.LOCAL_TIME) { LocalTime.from(it) }
        val DATE_TIME = createTemporalScalar(Constants.DATE_TIME) { OffsetDateTime.from(it) }
        val LOCAL_DATE_TIME = createTemporalScalar(Constants.LOCAL_DATE_TIME) { LocalDateTime.from(it) }

        fun parse(input: String): TemporalAccessor {
            try {
                return LocalDate.parse(input)
            } catch (ignore: Exception) {
            }
            try {
                return LocalTime.parse(input)
            } catch (ignore: Exception) {
            }
            try {
                return LocalDateTime.parse(input)
            } catch (ignore: Exception) {
            }
            try {
                return OffsetTime.parse(input, TIME_WITH_ZONE_FORMATTER)
            } catch (ignore: Exception) {
            }
            try {
                return OffsetDateTime.parse(input, OFFSET_DATE_TIME_FORMATTER)
            } catch (ignore: Exception) {
            }
            try {
                return OffsetDateTime.parse(input, CUSTOM_OFFSET_DATE_TIME_FORMATTER)
            } catch (ignore: Exception) {
            }
            try {
                return ZonedDateTime.parse(input)
            } catch (ignore: Exception) {
            }
            error("Input string cannot be parsed to any known Temporal type: $input")
        }

        private fun <T : TemporalAccessor> createTemporalScalar(
            name: String,
            transformer: (t: TemporalAccessor) -> T
        ): GraphQLScalarType {
            return GraphQLScalarType.newScalar()
                .name(name)
                .coercing(TemporalScalar<T>(transformer))
                .build()
        }
    }

}
