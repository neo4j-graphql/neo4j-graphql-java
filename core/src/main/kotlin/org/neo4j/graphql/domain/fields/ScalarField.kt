package org.neo4j.graphql.domain.fields

import graphql.language.ListType
import graphql.language.Type
import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.predicates.FieldOperator
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition

abstract class ScalarField(fieldName: String, typeMeta: TypeMeta, schemaConfig: SchemaConfig) :
    BaseField(fieldName, typeMeta), AuthableField, MutableField {

    open val predicates: Map<String, ScalarPredicateDefinition> = initPredicates(schemaConfig)

    private fun initPredicates(schemaConfig: SchemaConfig): Map<String, ScalarPredicateDefinition> {
        val fieldType = typeMeta.type.name()
        val resolver: ((comparisonResolver: (Expression, Expression) -> Condition) -> (Expression, Expression) -> Condition)? =
            if (fieldType == Constants.DURATION) {
                { comparisonResolver ->
                    { property, param ->
                        comparisonResolver(
                            Functions.datetime().add(property),
                            Functions.datetime().add(param)
                        )
                    }
                }
            } else {
                null
            }

        val result = mutableMapOf<String, ScalarPredicateDefinition>()
            .add(FieldOperator.EQUAL, resolver)
            .add(FieldOperator.NOT_EQUAL, resolver)
        if (fieldType == TypeBoolean.name) {
            return result
        }
        if (typeMeta.type.isList()) {
            result
                .add(FieldOperator.INCLUDES, resolver, typeMeta.type.inner())
                .add(FieldOperator.NOT_INCLUDES, resolver, typeMeta.type.inner())
            return result
        }
        result
            .add(FieldOperator.IN, resolver, ListType(typeMeta.type.inner().makeRequired(typeMeta.type.isRequired())))
            .add(
                FieldOperator.NOT_IN,
                resolver,
                ListType(typeMeta.type.inner().makeRequired(typeMeta.type.isRequired()))
            )
        if (STRING_LIKE_TYPES.contains(fieldType)) {
            result
                .add(FieldOperator.CONTAINS)
                .add(FieldOperator.NOT_CONTAINS)
                .add(FieldOperator.STARTS_WITH)
                .add(FieldOperator.NOT_STARTS_WITH)
                .add(FieldOperator.ENDS_WITH)
                .add(FieldOperator.NOT_ENDS_WITH)

            if (schemaConfig.enableRegex) {
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
    ): MutableMap<String, ScalarPredicateDefinition> {
        return this.add(op.suffix, delegate?.invoke(op.conditionCreator) ?: op.conditionCreator, type)
    }

    protected fun MutableMap<String, ScalarPredicateDefinition>.add(
        op: String,
        comparisonResolver: (Expression, Expression) -> Condition,
        type: Type<*>? = null, // TODO set correct type
    ): MutableMap<String, ScalarPredicateDefinition> {
        val name = this@ScalarField.fieldName + (if (op.isNotBlank()) "_$op" else "")
        this[name] = ScalarPredicateDefinition(name, this@ScalarField, comparisonResolver, type ?: typeMeta.whereType)
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
}
