package org.neo4j.graphql.domain.fields

import graphql.language.ListType
import graphql.language.Type
import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.Parameter
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.predicates.FieldOperator
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition

abstract class ScalarField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
    schemaConfig: SchemaConfig
) :
    BaseField(fieldName, typeMeta, annotations), AuthableField, MutableField {

    open val predicateDefinitions: Map<String, ScalarPredicateDefinition> = initPredicates(schemaConfig)

    val updateDefinitions: Map<String, ScalarUpdateOperation> = ScalarUpdateOperation
        .values()
        .filter { it.condition(this) }
        .associateBy { "${fieldName}_${it.name}" }


    private fun initPredicates(schemaConfig: SchemaConfig): Map<String, ScalarPredicateDefinition> {
        val fieldType = typeMeta.type.name()
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
        if (typeMeta.type.isList()) {
            result
                .add(FieldOperator.INCLUDES, resolver, typeMeta.type.inner())
            return result
        }
        result
            .add(FieldOperator.IN, resolver, ListType(typeMeta.type.inner().makeRequired(typeMeta.type.isRequired())))
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

        val comparisonEvaluator: (Any?, Any?) -> Boolean = { lhs, rhs ->
            if (delegate != null) {
                TODO()
            }
            op.conditionEvaluator(lhs, rhs)
        }
        return this.add(op.suffix, comparisonResolver, comparisonEvaluator, type, deprecated)
    }

    protected fun MutableMap<String, ScalarPredicateDefinition>.add(
        op: String,
        comparisonResolver: (Expression, Expression) -> Condition,
        comparisonEvaluator: (Any?, Any?) -> Boolean,
        type: Type<*>? = null, // TODO set correct type
        deprecated: String? = null,
    ): MutableMap<String, ScalarPredicateDefinition> {
        val name = this@ScalarField.fieldName + (if (op.isNotBlank()) "_$op" else "")
        this[name] = ScalarPredicateDefinition(
            name,
            this@ScalarField,
            comparisonResolver,
            comparisonEvaluator,
            type ?: typeMeta.whereType,
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

    open fun convertInputToCypher(input: Expression): Expression = when (typeMeta.type.name()) {
        Constants.DURATION ->
            if (Constants.JS_COMPATIBILITY) {
                input
            } else {
                Cypher.duration(input)
            }

        else -> input
    }

    override fun isNonGeneratedField(): Boolean {
        return this.annotations.id == null
                && !this.isCustomResolvable()
                && !this.isCypher()
    }

    override fun isEventPayloadField(): Boolean = !this.isCustomResolvable() &&
            (this.owner as? Interface)?.implementations?.values
                ?.none { it.getField(this.fieldName)?.isCustomResolvable() == true }
            ?: true


    fun isArrayUpdateOperationAllowed() = typeMeta.type.isList() &&
            (this is PrimitiveField // TODO remove after https://github.com/neo4j/graphql/issues/2677
                    || this is PointField)

    fun isIntOperationAllowed() =
        !typeMeta.type.isList() && (typeMeta.type.name() == Constants.INT || typeMeta.type.name() == Constants.BIG_INT)

    fun isFloatOperationAllowed() = !typeMeta.type.isList() && typeMeta.type.name() == Constants.FLOAT

    enum class ScalarUpdateOperation(val condition: (ScalarField) -> Boolean, val type: Type<*>? = null) {
        /**
         * Remove the last element from an array
         */
        POP(ScalarField::isArrayUpdateOperationAllowed, Constants.Types.Int),

        /**
         * Add an element to the end of an array
         */
        PUSH(ScalarField::isArrayUpdateOperationAllowed),
        INCREMENT(ScalarField::isIntOperationAllowed),
        DECREMENT(ScalarField::isIntOperationAllowed),
        ADD(ScalarField::isFloatOperationAllowed),
        SUBTRACT(ScalarField::isFloatOperationAllowed),
        DIVIDE(ScalarField::isFloatOperationAllowed),
        MULTIPLY(ScalarField::isFloatOperationAllowed),
    }
}
