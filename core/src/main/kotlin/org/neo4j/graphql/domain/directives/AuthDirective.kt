package org.neo4j.graphql.domain.directives

import graphql.language.*
import org.neo4j.graphql.*

data class AuthDirective(
    val rules: List<AuthRule>
) {

    open class BaseAuthRule(value: ObjectValue) {
        val isAuthenticated: Boolean? = value.get(BaseAuthRule::isAuthenticated)
        val allowUnauthenticated: Boolean? = value.get(BaseAuthRule::allowUnauthenticated)
        val allow: Map<String, Any>? = value.get(BaseAuthRule::allow, ::parseWildcardMap)
        val bind: Map<String, Any>? = value.get(BaseAuthRule::bind, ::parseWildcardMap)
        val where: Map<String, Any>? = value.get(BaseAuthRule::where, ::parseWildcardMap)
        val roles: List<String>? = value.get(BaseAuthRule::roles)
        val AND: List<BaseAuthRule>? = value.get(BaseAuthRule::AND, ::creteBaseAuthRule)
        val OR: List<BaseAuthRule>? = value.get(BaseAuthRule::OR, ::creteBaseAuthRule)

        companion object {
            private fun creteBaseAuthRule(v: Value<*>) =
                (v as ArrayValue).values.map { BaseAuthRule(it as ObjectValue) }

            @Suppress("UNCHECKED_CAST")
            private fun parseWildcardMap(v: Value<*>): Map<String, Any>? = when (v) {
                is NullValue -> null
                is StringValue -> emptyMap()
                is ObjectValue -> v.toJavaValue() as Map<String, Any>
                else -> throw IllegalArgumentException("value should be an object or `*`")
            }

        }

        override fun toString(): String {
            return "BaseAuthRule(isAuthenticated=$isAuthenticated, allowUnauthenticated=$allowUnauthenticated, allow=$allow, bind=$bind, where=$where, roles=$roles, AND=$AND, OR=$OR)"
        }


    }

    class AuthRule(rule: ObjectValue) : BaseAuthRule(rule) {
        val operations: List<AuthOperations>? = rule.get(AuthRule::operations) { field ->
            (field as? ArrayValue ?: throw IllegalArgumentException("auth rules rule operations should be a ListValue"))
                .values.map {
                    (it as? EnumValue
                        ?: throw  IllegalArgumentException("auth rules rule operations operation should be a EnumValue"))
                        .let {
                            AuthOperations.valueOf(it.name)
                        }
                }
        }

        override fun toString(): String {
            return "AuthRule(operations=$operations, isAuthenticated=$isAuthenticated, allowUnauthenticated=$allowUnauthenticated, allow=$allow, bind=$bind, where=$where, roles=$roles, AND=$AND, OR=$OR)"
        }
    }

    enum class AuthOperations { CREATE, READ, UPDATE, DELETE, CONNECT, DISCONNECT }

    companion object {
        fun create(directive: Directive): AuthDirective {
            directive.validateName(DirectiveConstants.AUTH)
            return AuthDirective(directive.readRequiredArgument(AuthDirective::rules) { value ->
                (value as? ArrayValue ?: throw IllegalArgumentException("auth rules must be a ArrayValue"))
                    .values.map {
                        AuthRule(
                            it as? ObjectValue
                                ?: throw IllegalArgumentException("auth rules rule should be a Object Value")
                        )
                    }
            })
        }
    }
}
