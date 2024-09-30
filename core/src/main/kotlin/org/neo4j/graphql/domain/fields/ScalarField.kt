package org.neo4j.graphql.domain.fields

import graphql.language.Type
import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.Parameter
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.predicates.FieldOperator
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition

abstract class ScalarField(
    fieldName: String,
    type: Type<*>,
    annotations: FieldAnnotations,
    schemaConfig: SchemaConfig
) :
    BaseField(fieldName, type, annotations) {

    open val predicateDefinitions: Map<String, ScalarPredicateDefinition> by lazy { initPredicates(schemaConfig) }

    private fun initPredicates(schemaConfig: SchemaConfig): Map<String, ScalarPredicateDefinition> {
        val fieldType = type.name()
        val resolver: ((comparisonResolver: (Expression, Expression) -> Condition) -> (Expression, Expression) -> Condition)? =
            if (fieldType == Constants.DURATION) {
                { comparisonResolver ->
                    { property, param ->
                        comparisonResolver(
                            Cypher.datetime().add(property),
                            Cypher.datetime().add(param)
                        )
                    }
                }
            } else {
                null
            }

        val result = mutableMapOf<String, ScalarPredicateDefinition>()
            .add(FieldOperator.EQUAL, resolver)
        if (fieldType == Constants.BOOLEAN) {
            return result
        }
        if (isList()) {
            result
                .add(FieldOperator.INCLUDES, resolver, type.inner())
            return result
        }
        result
            .add(FieldOperator.IN, resolver, type.inner().makeRequired(isRequired()).List)
        if (STRING_LIKE_TYPES.contains(fieldType)) {
            result
                .add(FieldOperator.CONTAINS)
                .add(FieldOperator.STARTS_WITH)
                .add(FieldOperator.ENDS_WITH)

            if ((fieldType == Constants.STRING && schemaConfig.features.filters.string.matches)
                || (fieldType == Constants.ID && schemaConfig.features.filters.id.matches)
            ) {
                result.add(FieldOperator.MATCHES)
            }
        }
        if (COMPARABLE_TYPES.contains(fieldType)) {
            val isString = fieldType == Constants.STRING
            if (!isString || schemaConfig.features.filters.string.lt) result.add(FieldOperator.LT, resolver)
            if (!isString || schemaConfig.features.filters.string.lte) result.add(FieldOperator.LTE, resolver)
            if (!isString || schemaConfig.features.filters.string.gt) result.add(FieldOperator.GT, resolver)
            if (!isString || schemaConfig.features.filters.string.gte) result.add(FieldOperator.GTE, resolver)
        }
        return result
    }

    protected fun MutableMap<String, ScalarPredicateDefinition>.add(
        op: FieldOperator,
        delegate: ((comparisonResolver: (Expression, Expression) -> Condition) -> (Expression, Expression) -> Condition)? = null,
        type: Type<*>? = null,
        deprecated: String? = null,
    ): MutableMap<String, ScalarPredicateDefinition> {
        val comparisonResolver: (Expression, Expression) -> Condition = { lhs, rhs ->
            val rhs2 = when (rhs) {
                is Parameter<*> -> convertInputToCypher(rhs)
                else -> rhs
            }
            (delegate?.invoke(op.conditionCreator) ?: op.conditionCreator)(lhs, rhs2)
        }

        return this.add(op.suffix, comparisonResolver, type, deprecated)
    }

    protected fun MutableMap<String, ScalarPredicateDefinition>.add(
        op: String,
        comparisonResolver: (Expression, Expression) -> Condition,
        type: Type<*>? = null, // TODO set correct type
        deprecated: String? = null,
    ): MutableMap<String, ScalarPredicateDefinition> {
        val name = this@ScalarField.fieldName + (if (op.isNotBlank()) "_$op" else "")
        this[name] = ScalarPredicateDefinition(
            name,
            this@ScalarField,
            comparisonResolver,
            type ?: when {
                isList() -> whereType
                    .let { if (this@ScalarField.type.isListElementRequired()) it.NonNull else it }
                    .List

                else -> whereType

            },
            deprecated
        )
        return this
    }

    companion object {
        private val COMPARABLE_TYPES = setOf(
            Constants.FLOAT,
            Constants.INT,
            Constants.STRING,
            Constants.BIG_INT,
            Constants.DATE_TIME,
            Constants.DATE,
            Constants.LOCAL_DATE_TIME,
            Constants.TIME,
            Constants.LOCAL_TIME,
            Constants.DURATION,
        )

        private val STRING_LIKE_TYPES = setOf(Constants.ID, Constants.STRING)
    }

    open fun convertInputToCypher(input: Expression): Expression = when (type.name()) {
        Constants.DURATION ->
            if (Constants.JS_COMPATIBILITY) {
                input
            } else {
                Cypher.duration(input)
            }

        else -> input
    }
}
