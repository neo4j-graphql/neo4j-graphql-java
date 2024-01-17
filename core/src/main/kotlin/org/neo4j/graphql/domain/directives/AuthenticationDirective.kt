package org.neo4j.graphql.domain.directives

import graphql.language.*
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.toJavaValue
import org.neo4j.graphql.validateName

data class AuthenticationDirective(
    val operations: Set<AuthenticationOperation>,
    val jwt: Map<String, Any>?
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

        fun create(directive: Directive): AuthenticationDirective {
            directive.validateName(NAME)


            return AuthenticationDirective(directive.readArgument(AuthenticationDirective::operations) { arrayValue ->
                (arrayValue as ArrayValue).values.map {
                    AuthenticationOperation.valueOf((it as EnumValue).name)
                }
            }?.toSet() ?: AuthenticationOperation.values().toSet(),

                directive.readArgument(AuthenticationDirective::jwt) { parseValue(it as ObjectValue) }
            )
        }
    }
}

