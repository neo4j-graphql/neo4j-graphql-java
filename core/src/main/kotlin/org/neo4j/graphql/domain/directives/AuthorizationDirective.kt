package org.neo4j.graphql.domain.directives

import graphql.language.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.model.inputs.AuthorizationWhere

data class AuthorizationDirective(
    val filter: List<FilterRule>,
    val validate: List<ValidationRule>,
) {

    fun initWhere(node: Node) {
        filter.forEach { it.initWhere(node) }
        validate.forEach { it.initWhere(node) }
    }

    sealed class BaseRule(
        private val rule: ObjectValue,
        private val jwtShape: Node?,
        excludedOperations: Set<AuthorizationOperation>,
    ) {
        val operations: Set<AuthorizationOperation> = rule.get(BaseRule::operations) { field ->
            (field as? ArrayValue
                ?: throw IllegalArgumentException("rule operations should be a ListValue"))
                .values.map { value ->
                    (value as? EnumValue
                        ?: throw IllegalArgumentException("rule operations operation should be a EnumValue"))
                        .let { AuthorizationOperation.valueOf(it.name) }
                        .also { op -> require(!excludedOperations.contains(op)) { "operation should not be in $excludedOperations" } }
                }
                .toSet()
        } ?: AuthorizationOperation.values().filterNot { excludedOperations.contains(it) }.toSet()

        val requireAuthentication: Boolean = rule.get(BaseRule::requireAuthentication) ?: true

        lateinit var where: AuthorizationWhere

        fun initWhere(node: Node) {
            where = AuthorizationWhere(node, jwtShape, rule.get(BaseRule::where).toDict())
        }
    }

    class FilterRule(
        rule: ObjectValue,
        jwtShape: Node?,
    ) : BaseRule(rule, jwtShape, setOf(AuthorizationOperation.CREATE))

    class ValidationRule(
        rule: ObjectValue,
        jwtShape: Node?,
    ) : BaseRule(rule, jwtShape, emptySet()) {

        val `when`: Set<AuthorizationValidateStage> = rule.get(ValidationRule::`when`) { field ->
            when (field) {
                is EnumValue -> return@get setOf(AuthorizationValidateStage.valueOf(field.name))
                is ArrayValue -> field.values
                    .map {
                        (it as? EnumValue ?: throw IllegalArgumentException("validation when should be a EnumValue"))
                            .let { AuthorizationValidateStage.valueOf(it.name) }
                    }
                    .toSet()

                else -> throw IllegalArgumentException("validation when should be an EnumValue or ListValue")
            }
        } ?: AuthorizationValidateStage.values().toSet()
    }

    enum class AuthorizationOperation { CREATE, READ, AGGREGATE, UPDATE, DELETE, CREATE_RELATIONSHIP, DELETE_RELATIONSHIP }
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

        fun create(directive: Directive, jwtShape: Node?): AuthorizationDirective {
            directive.validateName(NAME)
            return AuthorizationDirective(
                directive.readArgument(AuthorizationDirective::filter) { arrayValue ->
                    (arrayValue as ArrayValue).values.map { FilterRule(it as ObjectValue, jwtShape) }
                } ?: emptyList(),
                directive.readArgument(AuthorizationDirective::validate) { arrayValue ->
                    (arrayValue as ArrayValue).values.map { ValidationRule(it as ObjectValue, jwtShape) }
                } ?: emptyList(),

                )
        }
    }
}

