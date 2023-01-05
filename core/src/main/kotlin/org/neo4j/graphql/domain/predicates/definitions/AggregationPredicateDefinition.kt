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
    val type: Type<*>
) : PredicateDefinition {
    fun createCondition(lhs: Expression, rhs: Expression, ctx: QueryContext): Condition = when (method) {
        Method.NONE -> {
            val variable = ctx.getNextVariable()
            Predicates.any(variable).`in`(Functions.collect(lhs)).where(operator.conditionCreator(variable, rhs))
        }

        else -> operator.conditionCreator(method.functionCreator(lhs), rhs)
    }

    enum class Method(
        val suffix: String,
        val functionCreator: (Expression) -> Expression,
        val typeConverter: ((Type<*>, op: Operator) -> Type<*>) = { type, _ -> type.inner() },
        val condition: (Type<*>) -> Boolean = { true }
    ) {
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
            condition = { AGGREGATION_TYPES.contains(it.name()) }
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
        AVERAGE_SIZE(
            "AVERAGE", // TODO rename TO AVERAGE_SIZE? https://github.com/neo4j/graphql/issues/2661#issuecomment-1369669552
            { Functions.avg(Functions.size(it)) },
            typeConverter = { _, _ -> Constants.Types.Float },
            condition = { it.name() == Constants.STRING }),
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
        LONGEST(
            "LONGEST",
            { Functions.max(Functions.size(it)) },
            typeConverter = { _, _ -> Constants.Types.Int },
            condition = { it.name() == Constants.STRING }
        ),
        SHORTEST(
            "SHORTEST",
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
                            method.typeConverter(field.typeMeta.type, op)
                        )
                    }
            }
            .toMap()
    }

}
