package org.neo4j.graphql.domain.directives

import graphql.language.*
import org.neo4j.graphql.*

// TODO delete this file
data class AuthDirective(
    val rules: List<AuthRule>
) {

    open class BaseAuthRule(value: ObjectValue) {
        val isAuthenticated: Boolean? = value.get(BaseAuthRule::isAuthenticated)
        val allowUnauthenticated: Boolean? = value.get(BaseAuthRule::allowUnauthenticated)
        val allow: Map<String, Any>? = value.get(BaseAuthRule::allow, ::parseAuthPredicate)
        val bind: Map<String, Any>? = value.get(BaseAuthRule::bind, ::parseAuthPredicate)
        val where: Map<String, Any>? = value.get(BaseAuthRule::where, ::parseAuthPredicate)
        val roles: List<String>? = value.get(BaseAuthRule::roles)
        val AND: List<BaseAuthRule>? = value.get(BaseAuthRule::AND, ::creteBaseAuthRule)
        val OR: List<BaseAuthRule>? = value.get(BaseAuthRule::OR, ::creteBaseAuthRule)

        companion object {
            private fun creteBaseAuthRule(v: Value<*>) =
                (v as ArrayValue).values.map { BaseAuthRule(it as ObjectValue) }

            @Suppress("UNCHECKED_CAST")
            private fun parseAuthPredicate(v: Value<*>): Map<String, Any>? = when (v) {
                is NullValue -> null
                is StringValue -> emptyMap()
                is ObjectValue -> v.toJavaValue() as Map<String, Any>
                else -> throw IllegalArgumentException("value should be an object or `*`")
            }

        }

        override fun toString(): String {
            return "BaseAuthRule(isAuthenticated=$isAuthenticated, allowUnauthenticated=$allowUnauthenticated, allow=$allow, bind=$bind, where=$where, roles=$roles, AND=$AND, OR=$OR)"
        }

        fun hasWhereRule(): Boolean = where != null
                || OR?.find { it.hasWhereRule() } != null
                || AND?.find { it.hasWhereRule() } != null
    }

    class AuthRule(rule: ObjectValue) : BaseAuthRule(rule) {
        val operations: Set<AuthOperation>? = rule.get(AuthRule::operations) { field ->
            (field as? ArrayValue ?: throw IllegalArgumentException("auth rules rule operations should be a ListValue"))
                .values.map {
                    (it as? EnumValue
                        ?: throw IllegalArgumentException("auth rules rule operations operation should be a EnumValue"))
                        .let {
                            AuthOperation.valueOf(it.name)
                        }
                }
                .toSet()
        }

        override fun toString(): String {
            return "AuthRule(operations=$operations, isAuthenticated=$isAuthenticated, allowUnauthenticated=$allowUnauthenticated, allow=$allow, bind=$bind, where=$where, roles=$roles, AND=$AND, OR=$OR)"
        }
    }

    enum class AuthOperation { CREATE, READ, UPDATE, DELETE, CONNECT, DISCONNECT }

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

    fun hasWhereRule(): Boolean = rules.find { it.hasWhereRule() } != null
}
