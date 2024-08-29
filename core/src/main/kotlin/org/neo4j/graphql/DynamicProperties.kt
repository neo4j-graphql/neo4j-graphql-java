package org.neo4j.graphql

import graphql.Assert
import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.*
import graphql.schema.*
import java.util.*

object DynamicProperties {

    val INSTANCE: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name("DynamicProperties")
        .coercing(object : Coercing<Any, Any> {
            @Throws(CoercingSerializeException::class)
            override fun serialize(input: Any, graphQLContext: GraphQLContext, locale: Locale): Any {
                return input
            }

            @Throws(CoercingParseLiteralException::class)
            override fun parseValue(input: Any, graphQLContext: GraphQLContext, locale: Locale): Any {
                return input
            }

            @Throws(CoercingParseLiteralException::class)
            override fun parseLiteral(
                input: Value<*>,
                variables: CoercedVariables,
                graphQLContext: GraphQLContext,
                locale: Locale
            ): Any {
                return parse(input, emptyMap())
            }
        })
        .build()


    @Throws(CoercingParseLiteralException::class)
    private fun parse(input: Any, variables: Map<String, Any>): Any {
        return when (input) {
            !is Value<*> -> throw CoercingParseLiteralException("Expected AST type 'StringValue' but was '${input::class.java.simpleName}'.")
            is NullValue -> throw CoercingParseLiteralException("Expected non null value.")
            is ObjectValue -> input.objectFields.associate { it.name to parseNested(it.value, variables) }
            else -> Assert.assertShouldNeverHappen("Only maps structures are expected")
        }
    }

    @Throws(CoercingParseLiteralException::class)
    private fun parseNested(input: Any, variables: Map<String, Any>): Any? {
        return when (input) {
            !is Value<*> -> throw CoercingParseLiteralException("Expected AST type 'StringValue' but was '${input::class.java.simpleName}'.")
            is NullValue -> null
            is FloatValue -> input.value
            is StringValue -> input.value
            is IntValue -> input.value
            is BooleanValue -> input.isValue
            is EnumValue -> input.name
            is VariableReference -> variables[input.name]
            is ArrayValue -> input.values.map { v -> parseNested(v, variables) }
            is ObjectValue -> throw IllegalArgumentException("deep structures not supported for dynamic properties")
            else -> Assert.assertShouldNeverHappen("We have covered all Value types")
        }
    }

}
