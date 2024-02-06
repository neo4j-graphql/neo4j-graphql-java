package org.neo4j.graphql.domain.directives

import graphql.language.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.schema.model.inputs.JwtWhereInput
import org.neo4j.graphql.toDict
import org.neo4j.graphql.toJavaValue
import org.neo4j.graphql.validateName

data class AuthenticationDirective(
    val operations: Set<AuthenticationOperation>,
    val jwt: JwtWhereInput? = null,
) {

    enum class AuthenticationOperation { CREATE, READ, AGGREGATE, UPDATE, DELETE, CREATE_RELATIONSHIP, DELETE_RELATIONSHIP, SUBSCRIBE }

    companion object {
        const val NAME = "authentication"

        @Suppress("UNCHECKED_CAST")
        private fun parseValue(v: Value<*>): Map<String, Any>? = when (v) {
            is NullValue -> null
            is StringValue -> emptyMap()
            is ObjectValue -> v.toJavaValue() as Map<String, Any>
            else -> throw IllegalArgumentException("value should be an object or `*`")
        }

        fun create(directive: Directive, jwtShape: Node?): AuthenticationDirective {
            directive.validateName(NAME)


            val operations = directive.readArgument(AuthenticationDirective::operations) { arrayValue ->
                (arrayValue as ArrayValue).values.map {
                    AuthenticationOperation.valueOf((it as EnumValue).name)
                }
            }?.toSet() ?: AuthenticationOperation.values().toSet()

            val jwtWhereInput =
                jwtShape?.let { shape ->
                    directive.readArgument(AuthenticationDirective::jwt) { JwtWhereInput(shape, it.toDict()) }
                }
            return AuthenticationDirective(operations, jwtWhereInput)
        }
    }
}

