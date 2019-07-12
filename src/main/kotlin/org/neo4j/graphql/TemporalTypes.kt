package org.neo4j.graphql

import java.time.*
import java.time.temporal.Temporal

fun temporalTypeDefinitions(config: Translator.Context, types: Set<Class<Temporal>>) =
        when {
            config.temporal -> temporalTypeDefinitions.filter { types.contains(it.type) }
                .flatMap { listOf(it.output.second to it.input.second) }
                .joinToString("\n")
            else -> ""
        }

data class TypeDefinition(val name: String, val type: Class<out Temporal>, val output: Pair<String, String>, val input: Pair<String, String>)

val temporalTypeDefinitions = listOf(
        TypeDefinition(
                "LocalTime", OffsetTime::class.java,
                "_Neo4jTime" to """
        type _Neo4jTime {
            hour: Int
            minute: Int
            second: Int
            millisecond: Int
            microsecond: Int
            nanosecond: Int
            timezone: String
            formatted: String
        }
        """,
                "_Neo4jTimeInput" to """
        input _Neo4jTimeInput {
            hour: Int
            minute: Int
            second: Int
            nanosecond: Int
            millisecond: Int
            microsecond: Int
            timezone: String
            formatted: String
        }
        """),
        TypeDefinition(
                "Date", LocalDate::class.java,
                "_Neo4jDate" to """
        type _Neo4jDate {
            year: Int
            month: Int
            day: Int
            formatted: String
        }
        """,
                "_Neo4jDateInput" to """
        input _Neo4jDateInput {
            year: Int
            month: Int
            day: Int
            formatted: String
        }
        """),
        TypeDefinition(
                "DateTime", LocalDateTime::class.java,
                "_Neo4jDateTime" to """
        type _Neo4jDateTime {
            year: Int
            month: Int
            day: Int
            hour: Int
            minute: Int
            second: Int
            millisecond: Int
            microsecond: Int
            nanosecond: Int
            timezone: String
            formatted: String
        }
        """,
                "_Neo4jDateTimeInput" to """
        input _Neo4jDateTimeInput {
            year: Int
            month: Int
            day: Int
            hour: Int
            minute: Int
            second: Int
            millisecond: Int
            microsecond: Int
            nanosecond: Int
            timezone: String
            formatted: String
        }
        """),
        TypeDefinition(
                "Time", Instant::class.java,
                "_Neo4jLocalTime" to """
        type _Neo4jLocalTime {
            hour: Int
            minute: Int
            second: Int
            millisecond: Int
            microsecond: Int
            nanosecond: Int
            formatted: String
        }
        """,
                "_Neo4jLocalTimeInput" to """
        input _Neo4jLocalTimeInput {
            hour: Int
            minute: Int
            second: Int
            millisecond: Int
            microsecond: Int
            nanosecond: Int
            formatted: String
        }
        """),
        TypeDefinition(
                "LocalDateTime", OffsetDateTime::class.java,
                "_Neo4jLocalDateTime" to """
        type _Neo4jLocalDateTime {
            year: Int
            month: Int
            day: Int
            hour: Int
            minute: Int
            second: Int
            millisecond: Int
            microsecond: Int
            nanosecond: Int
            formatted: String
        }
        """,
                "_Neo4jLocalDateTimeInput" to """
        input _Neo4jLocalDateTimeInput {
            year: Int
            month: Int
            day: Int
            hour: Int
            minute: Int
            second: Int
            millisecond: Int
            microsecond: Int
            nanosecond: Int
            formatted: String
        }
        """)
)