package org.neo4j.graphql.domain.predicates.definitions

import graphql.language.Type
import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.fields.PrimitiveField

data class AggregationPredicateDefinition(
    override val name: String,
    val field: PrimitiveField,
    val method: Method,
    val operator: Operator,
    val type: Type<*>,
    // TODO remove deprecated
    val deprecated: String? = null
) : PredicateDefinition {
    fun createCondition(lhs: Expression, rhs: Expression, ctx: QueryContext): Condition {
        val convertedRhs = wrapExpression(rhs)
        return when (method) {
            Method.NONE -> {
                val variable = ctx.getNextVariable()
                Predicates.any(variable).`in`(Functions.collect(lhs)).where(
                    operator.conditionCreator(
                        wrapExpression(variable),
                        convertedRhs
                    )
                )
            }

            else -> operator.conditionCreator(wrapExpression(method.functionCreator(lhs)), convertedRhs)
        }
    }

    private fun wrapExpression(expression: Expression): Expression = if (type.name() == Constants.DURATION) {
        Functions.datetime().add(expression)
    } else {
        expression
    }

    enum class Method(
        val suffix: String,
        val functionCreator: (Expression) -> Expression,
        val typeConverter: ((Type<*>, op: Operator) -> Type<*>) = { type, _ -> type.inner() },
        val condition: (Type<*>) -> Boolean = { true },
        val deprecation: (Type<*>) -> String? = { null }
    ) {
        @Deprecated("Please use the explicit _LENGTH version for string aggregation.")
        NONE(
            "",
            { it },
            typeConverter = { type, op ->
                when (type.name()) {
                    //TODO why this inconsistency? https://github.com/neo4j/graphql/issues/2661
                    Constants.STRING -> if (op == Operator.EQUAL) Constants.Types.String else Constants.Types.Int
                    else -> type.inner()
                }
            },
            condition = { AGGREGATION_TYPES.contains(it.name()) },
            deprecation = { "Aggregation filters that are not relying on an aggregating function will be deprecated." }
        ),
        AVERAGE(
            "AVERAGE",
            Functions::avg,
            typeConverter = { type, _ ->
                when (type.name()) {
                    Constants.BIG_INT, Constants.DURATION -> type.inner()
                    else -> Constants.Types.Float
                }
            },
            condition = { AGGREGATION_AVERAGE_TYPES.contains(it.name()) && it.name() != Constants.STRING }),
        AVERAGE_LENGTH(
            "AVERAGE_LENGTH",
            { Functions.avg(Functions.size(it)) },
            typeConverter = { _, _ -> Constants.Types.Float },
            condition = { it.name() == Constants.STRING }),

        @Deprecated("Please use the explicit _LENGTH version for string aggregation.")
        AVERAGE_SIZE(
            "AVERAGE", // TODO rename TO AVERAGE_SIZE? https://github.com/neo4j/graphql/issues/2661#issuecomment-1369669552
            { Functions.avg(Functions.size(it)) },
            typeConverter = { _, _ -> Constants.Types.Float },
            condition = { it.name() == Constants.STRING },
            deprecation = { "Please use the explicit _LENGTH version for string aggregation." }
        ),
        SUM(
            "SUM",
            Functions::sum,
            condition = { AGGREGATION_AVERAGE_TYPES.contains(it.name()) && it.name() != Constants.STRING && it.name() != Constants.DURATION }),
        MIN(
            "MIN",
            Functions::min,
            condition = { AGGREGATION_TYPES.contains(it.name()) && it.name() != Constants.STRING && it.name() != Constants.ID }
        ),
        MAX(
            "MAX",
            Functions::max,
            condition = { AGGREGATION_TYPES.contains(it.name()) && it.name() != Constants.STRING && it.name() != Constants.ID }
        ),

        @Deprecated("Please use the explicit _LENGTH version for string aggregation.")
        LONGEST(
            "LONGEST",
            { Functions.max(Functions.size(it)) },
            typeConverter = { _, _ -> Constants.Types.Int },
            condition = { it.name() == Constants.STRING },
            deprecation = { "Please use the explicit _LENGTH version for string aggregation." }
        ),

        @Deprecated("Please use the explicit _LENGTH version for string aggregation.")
        SHORTEST(
            "SHORTEST",
            { Functions.min(Functions.size(it)) },
            typeConverter = { _, _ -> Constants.Types.Int },
            condition = { it.name() == Constants.STRING },
            deprecation = { "Please use the explicit _LENGTH version for string aggregation." }
        ),
        LONGEST_LENGTH(
            "LONGEST_LENGTH",
            { Functions.max(Functions.size(it)) },
            typeConverter = { _, _ -> Constants.Types.Int },
            condition = { it.name() == Constants.STRING }
        ),
        SHORTEST_LENGTH(
            "SHORTEST_LENGTH",
            { Functions.min(Functions.size(it)) },
            typeConverter = { _, _ -> Constants.Types.Int },
            condition = { it.name() == Constants.STRING }
        ),
    }

    enum class Operator(
        val suffix: String,
        val conditionCreator: (Expression, Expression) -> Condition,
        val condition: (Type<*>) -> Boolean = { true }
    ) {
        EQUAL("EQUAL", Expression::eq),
        LT("LT", Expression::lt, condition = { it.name() != Constants.ID }),
        LTE("LTE", Expression::lte, condition = { it.name() != Constants.ID }),
        GT("GT", Expression::gt, condition = { it.name() != Constants.ID }),
        GTE("GTE", Expression::gte, condition = { it.name() != Constants.ID }),
    }

    companion object {
        private val AGGREGATION_AVERAGE_TYPES = setOf(
            Constants.STRING,
            Constants.INT,
            Constants.FLOAT,
            Constants.BIG_INT,
            Constants.DURATION
        )

        private val AGGREGATION_TYPES =
            setOf(
                Constants.ID,
                Constants.STRING,
                Constants.INT,
                Constants.FLOAT,
                Constants.BIG_INT,
                Constants.DATE_TIME,
                Constants.LOCAL_DATE_TIME,
                Constants.LOCAL_TIME,
                Constants.TIME,
                Constants.DURATION
            )

        fun create(field: PrimitiveField) = Method.values()
            .filter { it.condition(field.typeMeta.type) }
            .flatMap { method ->
                Operator.values()
                    .filterNot { field.typeMeta.type.isList() }
                    .filter { it.condition(field.typeMeta.type) }
                    .map { op ->
                        val name =
                            "${field.fieldName}_${method.suffix}${"_".takeIf { method.suffix.isNotEmpty() } ?: ""}${op.suffix}"
                        name to AggregationPredicateDefinition(
                            name, field, method, op,
                            method.typeConverter(field.typeMeta.type, op),
                            method.deprecation(field.typeMeta.type)
                        )
                    }
            }
            .toMap()
    }

}
