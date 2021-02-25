package org.neo4j.graphql

import graphql.language.Field
import graphql.language.ObjectValue
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLFieldsContainer

const val NEO4j_FORMATTED_PROPERTY_KEY = "formatted"
const val NEO4j_POINT_DISTANCE_FILTER = "_Neo4jPointDistanceFilter"
const val NEO4j_POINT_DISTANCE_FILTER_SUFFIX = "_distance"

data class TypeDefinition(
        val name: String,
        val typeDefinition: String,
        val inputDefinition: String = typeDefinition + "Input"
)

data class Neo4jConverter(val parse: String = "") {
    fun parseValue(strArg: String): String {
        if (parse.isBlank()) {
            return "\$$strArg"
        }
        return "$parse(\$$strArg)"
    }
}

fun getNeo4jTypeConverter(name: String): Neo4jConverter {
    return if (name.startsWith("_Neo4j")) {
        val neo4jType = neo4jTypeDefinitions.find { name == it.inputDefinition || name == it.typeDefinition }
                ?: throw RuntimeException("Type $name not found")
        return Neo4jConverter(neo4jType.name.toLowerCase())
    } else {
        Neo4jConverter()
    }
}

data class Neo4jQueryConversion(val name: String, val propertyName: String, val converter: Neo4jConverter = Neo4jConverter()) {
    companion object {
        fun forQuery(argument: Translator.CypherArgument, field: Field, type: GraphQLFieldsContainer): Neo4jQueryConversion {
            val isNeo4jType = type.isNeo4jType()
            val name = argument.name
            return when (isNeo4jType) {
                true -> {
                    if (name == NEO4j_FORMATTED_PROPERTY_KEY) {
                        Neo4jQueryConversion(field.name + NEO4j_FORMATTED_PROPERTY_KEY.capitalize(), field.name, getNeo4jTypeConverter(type.name))
                    } else {
                        Neo4jQueryConversion(field.name + name.capitalize(), field.name + ".$name")
                    }
                }
                false -> Neo4jQueryConversion(name, argument.propertyName)
            }
        }


        fun forMutation(value: Any, fieldDefinition: GraphQLFieldDefinition): Neo4jQueryConversion {
            val isNeo4jType = fieldDefinition.isNeo4jType()
            val name = fieldDefinition.name
            if (!isNeo4jType) {
                Neo4jQueryConversion(name, name)
            }
            val converter = getNeo4jTypeConverter(fieldDefinition.type.innerName())
            val objectValue = (value as? ObjectValue)
                ?.objectFields
                ?.map { it.name to it.value }
                ?.toMap()
                    ?: return Neo4jQueryConversion(name, name, converter)
            return if (objectValue.contains(NEO4j_FORMATTED_PROPERTY_KEY)) {
                Neo4jQueryConversion("$name.$NEO4j_FORMATTED_PROPERTY_KEY", name, converter)
            } else {
                Neo4jQueryConversion(name, name, converter)
            }
        }
    }
}

val neo4jTypeDefinitions = listOf(
        TypeDefinition("LocalTime", "_Neo4jTime"),
        TypeDefinition("Date", "_Neo4jDate"),
        TypeDefinition("DateTime", "_Neo4jDateTime"),
        TypeDefinition("Time", "_Neo4jLocalTime"),
        TypeDefinition("LocalDateTime", "_Neo4jLocalDateTime"),
        TypeDefinition("Point", "_Neo4jPoint")
)
