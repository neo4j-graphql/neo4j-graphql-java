package org.neo4j.graphql

import graphql.Assert
import graphql.language.*
import graphql.schema.*

object DynamicProperties {

    val INSTANCE: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("DynamicProperties")
        .coercing(object : Coercing<Any, Any> {
            @Throws(CoercingSerializeException::class)
            override fun serialize(input: Any): Any {
                return input
            }

            @Throws(CoercingParseValueException::class)
            override fun parseValue(input: Any): Any {
                return input
            }

            @Throws(CoercingParseLiteralException::class)
            override fun parseLiteral(o: Any): Any? {
                return parse(o, emptyMap())
            }
        })
        .build()


    @Throws(CoercingParseLiteralException::class)
    private fun parse(input: Any, variables: Map<String, Any>): Any? {
        if (input !is Value<*>) {
            throw CoercingParseLiteralException("Expected AST type 'StringValue' but was '${input::class.java.simpleName}'.")
        } else if (input is NullValue) {
            return null
        } else if (input is FloatValue) {
            return input.value
        } else if (input is StringValue) {
            return input.value
        } else if (input is IntValue) {
            return input.value
        } else if (input is BooleanValue) {
            return input.isValue
        } else if (input is EnumValue) {
            return input.name
        } else if (input is VariableReference) {
            val varName = input.name
            return variables[varName]
        } else {
            val values: List<*>
            return when (input) {
                is ArrayValue -> {
                    values = input.values
                    values.map { v -> parse(v, variables) }
                }
                is ObjectValue -> {
                    throw IllegalArgumentException("deep structures not supported for dynamic properties")
                }
                else -> Assert.assertShouldNeverHappen("We have covered all Value types")
            }
        }
    }

}
