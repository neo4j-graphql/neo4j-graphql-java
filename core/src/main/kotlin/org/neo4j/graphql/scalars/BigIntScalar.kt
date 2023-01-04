package org.neo4j.graphql.scalars

import graphql.Assert
import graphql.language.*
import graphql.schema.*
import org.neo4j.graphql.Constants

object BigIntScalar {

    val INSTANCE: GraphQLScalarType = GraphQLScalarType.newScalar()
        .name(Constants.BIG_INT)
        .coercing(object : Coercing<Number, Any> {
            @Throws(CoercingSerializeException::class)
            override fun serialize(input: Any): Any {
                return input.toString()
            }

            @Throws(CoercingParseValueException::class)
            override fun parseValue(input: Any): Number {
                return parseLiteral(input)
            }

            @Throws(CoercingParseLiteralException::class)
            override fun parseLiteral(input: Any): Number {
                return when (input) {
                    is StringValue -> input.value.toLong()
                    is FloatValue -> input.value
                    is IntValue -> input.value
                    else -> Assert.assertShouldNeverHappen("Only string or number is expected")
                }
            }
        })
        .build()

}
