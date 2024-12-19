package org.neo4j.graphql.utils

import com.jayway.jsonpath.JsonPath
import org.assertj.core.api.Assertions
import org.assertj.core.api.InstanceOfAssertFactories
import org.neo4j.graphql.asciidoc.ast.Table
import org.neo4j.graphql.scalars.TemporalScalar
import org.threeten.extra.PeriodDuration
import java.math.BigInteger
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAccessor
import java.util.function.Consumer

object Assertions {

    fun assertEqualIgnoreOrder(expected: Any?, actual: Any?) {
        when (expected) {
            is Map<*, *> -> {
                val mapAssert = Assertions.assertThat(actual).asInstanceOf(InstanceOfAssertFactories.MAP)
                    .hasSize(expected.size)
                    .containsOnlyKeys(*expected.keys.toTypedArray())
                expected.forEach { (key, value) ->
                    mapAssert.extractingByKey(key)
                        .satisfies(Consumer { assertEqualIgnoreOrder(value, (actual as Map<*, *>)[key]) })
                }
            }

            is Collection<*> -> {
                val assertions: List<Consumer<Any>> =
                    expected.map { e -> Consumer<Any> { a -> assertEqualIgnoreOrder(e, a) } }
                Assertions.assertThat(actual).asInstanceOf(InstanceOfAssertFactories.LIST)
                    .hasSize(expected.size)
                    .withFailMessage { "Expecting actual:\n  ${actual}\n to satisfy\n  ${expected}\n in any order." }
                    .satisfiesExactlyInAnyOrder(*assertions.toTypedArray())
            }

            else -> Assertions.assertThat(actual).isEqualTo(expected)
        }
    }

    fun assertWithJsonPath(table: Table, result: Any?) {
        for (record in table.records) {
            val jsonPath = record.get("Path")
            val condition = record.get("Condition")
            val expectedValue = record.get("Expected Value")

            val actualValue = JsonPath.read<Any>(result, jsonPath)

            // try to convert the expected value to the type of the current value
            val typedExpectedValue = if (expectedValue.isNullOrBlank()) {
                null
            } else when (actualValue) {
                is Int -> expectedValue.toInt()
                else -> expectedValue
            }

            val assertion = Assertions.assertThat(actualValue).describedAs(jsonPath)
            when (condition) {
                "==" -> assertion.isEqualTo(typedExpectedValue)
                ">=" -> when (actualValue) {
                    is Int -> assertion.asInstanceOf(InstanceOfAssertFactories.INTEGER)
                        .isGreaterThanOrEqualTo(typedExpectedValue as? Int)

                    else -> TODO()
                }

                "notEmpty" -> {
                    when (actualValue) {
                        is Collection<*> -> {
                            assertion.asInstanceOf(InstanceOfAssertFactories.COLLECTION).isNotEmpty()
                        }

                        is String -> {
                            assertion.asInstanceOf(InstanceOfAssertFactories.STRING).isNotEmpty()
                        }

                        else -> {
                            TODO()
                        }
                    }
                }

                else -> TODO("$condition not implemented")
            }
        }
    }

    fun assertCypherParams(expected: Map<String, Any?>, actual: Map<String, Any?>) {
        Assertions.assertThat(actual).asInstanceOf(InstanceOfAssertFactories.MAP)
            .hasSize(expected.size)
            .containsOnlyKeys(*expected.keys.toTypedArray())
            .allSatisfy({ key, value -> assertCypherParam(expected[key], value) })
    }

    private fun assertCypherParam(expected: Any?, actual: Any?) {
        when (expected) {
            is Int -> when (actual) {
                is Double -> {
                    Assertions.assertThat(actual).isEqualTo(expected.toDouble())
                    return
                }

                is Long -> {
                    Assertions.assertThat(actual).isEqualTo(expected.toLong())
                    return
                }

                is BigInteger -> {
                    Assertions.assertThat(actual).isEqualTo(expected.toBigInteger())
                    return
                }
            }

            is String -> {
                when (actual) {
                    is TemporalAccessor -> {
                        Assertions.assertThat(actual).isEqualTo(TemporalScalar.parse(expected))
                        return
                    }
                }
                try {
                    val expectedDate = ZonedDateTime.parse(expected)
                    val actualDate = ZonedDateTime.parse(actual as String)
                    Assertions.assertThat(actualDate).isEqualTo(expectedDate)
                    return
                } catch (ignore: DateTimeParseException) {
                }
                try {
                    val expectedDate = LocalDateTime.parse(expected)
                    val actualDate = LocalDateTime.parse(actual as String)
                    Assertions.assertThat(actualDate).isEqualTo(expectedDate)
                    return
                } catch (ignore: DateTimeParseException) {
                }
                try {
                    val expectedTime = LocalDate.parse(expected, DateTimeFormatter.ISO_DATE)
                    val actualTime = LocalDate.parse(actual as String)
                    Assertions.assertThat(actualTime).isEqualTo(expectedTime)
                    return
                } catch (ignore: DateTimeParseException) {
                }
                try {
                    val expectedTime = LocalTime.parse(expected, DateTimeFormatter.ISO_TIME)
                    val actualTime = LocalTime.parse(actual as String)
                    Assertions.assertThat(actualTime).isEqualTo(expectedTime)
                    return
                } catch (ignore: DateTimeParseException) {
                }
                try {
                    PeriodDuration.parse(expected)?.let { expectedDuration ->
                        when (actual) {
                            is PeriodDuration -> Assertions.assertThat(actual).isEqualTo(expectedDuration)
                            is Period -> Assertions.assertThat(PeriodDuration.of(actual))
                                .isEqualTo(expectedDuration)

                            is Duration -> Assertions.assertThat(PeriodDuration.of(actual))
                                .isEqualTo(expectedDuration)

                            is String -> Assertions.assertThat(actual).isEqualTo(expected)
                            else -> Assertions.fail<Any>("Unexpected type ${actual!!::class.java}")
                        }
                        return
                    }
                } catch (ignore: DateTimeParseException) {
                }
            }

            is Map<*, *> -> {
                if (actual is Number) {
                    val low = expected["low"] as? Number
                    val high = expected["high"] as? Number
                    if (low != null && high != null && expected.size == 2) {
                        // this is to convert the js bigint into a java long
                        val highLong = high.toLong() shl 32
                        val lowLong = low.toLong() and 0xFFFFFFFFL
                        val expectedLong = highLong or lowLong
                        when (actual) {
                            is BigInteger -> Assertions.assertThat(actual.toLong()).isEqualTo(expectedLong)
                            is Int -> Assertions.assertThat(actual.toLong()).isEqualTo(expectedLong)
                            is Long -> Assertions.assertThat(actual).isEqualTo(expectedLong)
                            else -> Assertions.fail<Any>("Unexpected type ${actual::class.java}")
                        }
                        return
                    }
                }
                if (actual is Map<*, *>) {
                    Assertions.assertThat(actual).hasSize(expected.count())
                    expected.forEach { key, value ->
                        assertCypherParam(value, actual[key])
                    }
                    return
                }
            }

            is Iterable<*> -> {
                if (actual is Iterable<*>) {
                    Assertions.assertThat(actual).hasSize(expected.count())
                    expected.forEachIndexed { index, expectedValue ->
                        assertCypherParam(expectedValue, actual.elementAt(index))
                    }
                    return
                }
            }
        }
        Assertions.assertThat(actual).isEqualTo(expected)
    }
}
