package org.neo4j.graphql.domain.directives

import graphql.language.*
import org.neo4j.graphql.get
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.toJavaValue
import org.neo4j.graphql.validateName

data class AuthorizationDirective(
    val filter: List<FilterRule>,
    val validate: List<ValidationRule>,
) {


    class FilterRule(
        rule: ObjectValue,
    ) {

        val operations: Set<AuthorizationFilterOperation> = rule.get(FilterRule::operations) { field ->
            (field as? ArrayValue
                ?: throw IllegalArgumentException("filter rules rule operations should be a ListValue"))
                .values.map {
                    (it as? EnumValue
                        ?: throw IllegalArgumentException("filter rules rule operations operation should be a EnumValue"))
                        .let {
                            AuthorizationFilterOperation.valueOf(it.name)
                        }
                }
                .toSet()
        } ?: AuthorizationFilterOperation.values().toSet()

        val requireAuthentication: Boolean = rule.get(FilterRule::requireAuthentication) ?: true
        val where: AuthorizationWhere? = rule.get(FilterRule::where) { AuthorizationWhere(it as ObjectValue) }
    }

    class ValidationRule(
        rule: ObjectValue,
    ) {

        val operations: Set<AuthorizationValidateOperation> = rule.get(ValidationRule::operations) { field ->
            (field as? ArrayValue
                ?: throw IllegalArgumentException("validation rules rule operations should be a ListValue"))
                .values.map {
                    (it as? EnumValue
                        ?: throw IllegalArgumentException("validation rules rule operations operation should be a EnumValue"))
                        .let {
                            AuthorizationValidateOperation.valueOf(it.name)
                        }
                }
                .toSet()
        } ?: AuthorizationValidateOperation.values().toSet()

        val `when`: Set<AuthorizationValidateStage> = rule.get(ValidationRule::`when`) { field ->
            (field as? ArrayValue
                ?: throw IllegalArgumentException("validation when should be a ListValue"))
                .values.map {
                    (it as? EnumValue
                        ?: throw IllegalArgumentException("validation when should be a EnumValue"))
                        .let {
                            AuthorizationValidateStage.valueOf(it.name)
                        }
                }
                .toSet()
        } ?: AuthorizationValidateStage.values().toSet()

        val requireAuthentication: Boolean = rule.get(FilterRule::requireAuthentication) ?: true
        val where: AuthorizationWhere? = rule.get(FilterRule::where) { AuthorizationWhere(it as ObjectValue) }
    }

    class AuthorizationWhere(
        value: ObjectValue,
    ) {
        val AND: List<AuthorizationWhere>? = value.get(AuthorizationWhere::AND, ::creteBaseAuthRule)
        val OR: List<AuthorizationWhere>? = value.get(AuthorizationWhere::OR, ::creteBaseAuthRule)
        val NOT: AuthorizationWhere? = value.get(AuthorizationWhere::NOT) { AuthorizationWhere(it as ObjectValue) }
        val node: Map<String, Any>? = value.get(AuthorizationWhere::node, ::parsePredicate)
        val jwt: Map<String, Any>? = value.get(AuthorizationWhere::jwt, ::parsePredicate)

        companion object {
            private fun creteBaseAuthRule(v: Value<*>) =
                (v as ArrayValue).values.map { AuthorizationWhere(it as ObjectValue) }
        }
    }

    enum class AuthorizationFilterOperation { READ, AGGREGATE, UPDATE, DELETE, CREATE_RELATIONSHIP, DELETE_RELATIONSHIP }
    enum class AuthorizationValidateOperation { CREATE, READ, AGGREGATE, UPDATE, DELETE, CREATE_RELATIONSHIP, DELETE_RELATIONSHIP }
    enum class AuthorizationValidateStage { BEFORE, AFTER }

    companion object {
        const val NAME = "authorization"

        @Suppress("UNCHECKED_CAST")
        private fun parsePredicate(v: Value<*>): Map<String, Any>? = when (v) {
            is NullValue -> null
            is StringValue -> emptyMap()
            is ObjectValue -> v.toJavaValue() as Map<String, Any>
            else -> throw IllegalArgumentException("value should be an object or `*`")
        }

        fun create(directive: Directive): AuthorizationDirective {
            directive.validateName(NAME)
            return AuthorizationDirective(
                directive.readArgument(AuthorizationDirective::filter) { arrayValue ->
                    (arrayValue as ArrayValue).values.map { FilterRule(it as ObjectValue) }
                } ?: emptyList(),
                directive.readArgument(AuthorizationDirective::validate) { arrayValue ->
                    (arrayValue as ArrayValue).values.map { ValidationRule(it as ObjectValue) }
                } ?: emptyList(),

                )
        }
    }
}

