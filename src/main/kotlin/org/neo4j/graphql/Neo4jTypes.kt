package org.neo4j.graphql

import graphql.language.Field
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLType
import java.time.*
import java.time.temporal.Temporal

fun neo4jTypesSdl() = neo4jTypeDefinitions
    .map { "${it.output.second} ${it.input.second}" }
    .joinToString("\n")

const val NEO4j_FORMATTED_PROPERTY_KEY = "formatted"

data class TypeDefinition(val name: String, val type: Class<out Temporal>, val output: Pair<String, String>, val input: Pair<String, String>)


data class Neo4jConverter(val parse: String = "") {
    fun parseValue(strArg: String): String {
        if (parse.isBlank()) {
            return "\$$strArg"
        }
        return "$parse(\$$strArg)"
    }
}

fun getNeo4jTypeConverter(type: GraphQLType): Neo4jConverter {
    return if (type.isNeo4jType()) {
        val neo4jType = neo4jTypeDefinitions.find { type.name == it.input.first || type.name == it.output.first }
                ?: throw RuntimeException("Type ${type.name} not found")
        return Neo4jConverter(neo4jType.name.toLowerCase())
    } else {
        Neo4jConverter()
    }
}

data class Neo4jQueryConversion(val name: String, val propertyName: String, val converter: Neo4jConverter = Neo4jConverter()) {
    companion object {
        fun forQuery(argument: Translator.CypherArgument, field: Field, fieldDefinition: GraphQLFieldDefinition): Neo4jQueryConversion {
            val isNeo4jType = fieldDefinition.isNeo4jType()
            val name = argument.name
            return when (isNeo4jType) {
                true -> {
                    if (name == NEO4j_FORMATTED_PROPERTY_KEY) {
                        Neo4jQueryConversion(field.name + NEO4j_FORMATTED_PROPERTY_KEY.capitalize(), field.name, getNeo4jTypeConverter(fieldDefinition.type.inner()))
                    } else {
                        Neo4jQueryConversion(field.name + name.capitalize(), field.name + ".$name")
                    }
                }
                false -> Neo4jQueryConversion(name, argument.propertyName)
            }
        }


        fun forMutation(argument: Translator.CypherArgument, fieldDefinition: GraphQLFieldDefinition): Neo4jQueryConversion {
            val isNeo4jType = fieldDefinition.type.isNeo4jType()
            val name = argument.name
            return when (isNeo4jType) {
                true -> {
                    val converter = getNeo4jTypeConverter(fieldDefinition.type)
                    when (argument.value) {
                        is Map<*, *> -> if (argument.value.contains(NEO4j_FORMATTED_PROPERTY_KEY)) {
                            Neo4jQueryConversion("$name.$NEO4j_FORMATTED_PROPERTY_KEY", name, converter)
                        } else {
                            Neo4jQueryConversion(name, name, converter)
                        }
                        else -> Neo4jQueryConversion(name, name, converter)
                    }
                }
                false -> Neo4jQueryConversion(argument.name, argument.name)
            }
        }
    }
}


val neo4jTypeDefinitions = listOf(
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