package org.neo4j.graphql.domain.predicates

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression

enum class FieldOperator(
    val suffix: String,
    val conditionCreator: (Expression, Expression) -> Condition,
    val conditionEvaluator: (Any?, Any?) -> Boolean,
) {
    EQUAL("",
        { lhs, rhs -> if (rhs == Cypher.literalNull()) lhs.isNull else lhs.eq(rhs) },
        { lhs, rhs -> lhs == rhs }
    ),
    NOT_EQUAL("NOT",
        { lhs, rhs -> if (rhs == Cypher.literalNull()) lhs.isNotNull else lhs.eq(rhs).not() },
        { lhs, rhs -> lhs != rhs }),
    LT("LT",
        Expression::lt,
        { lhs, rhs -> if (lhs is Number && rhs is Number) lhs.toDouble() < rhs.toDouble() else TODO() }),
    LTE("LTE",
        Expression::lte,
        { lhs, rhs -> if (lhs is Number && rhs is Number) lhs.toDouble() <= rhs.toDouble() else TODO() }),
    GT(
        "GT",
        Expression::gt,
        { lhs, rhs -> if (lhs is Number && rhs is Number) lhs.toDouble() > rhs.toDouble() else TODO() },
    ),
    GTE(
        "GTE",
        Expression::gte,
        { lhs, rhs -> if (lhs is Number && rhs is Number) lhs.toDouble() >= rhs.toDouble() else TODO() },
    ),

    MATCHES("MATCHES",
        Expression::matches,
        { lhs, rhs -> if (lhs is String && rhs is String) lhs.matches(rhs.toRegex()) else TODO() }
    ),

    CONTAINS("CONTAINS",
        Expression::contains,
        { lhs, rhs -> if (lhs is String && rhs is String) lhs.contains(rhs) else TODO() }
    ),
    NOT_CONTAINS(
        "NOT_CONTAINS",
        { lhs, rhs -> lhs.contains(rhs).not() },
        { lhs, rhs -> if (lhs is String && rhs is String) lhs.contains(rhs).not() else TODO() },
    ),

    STARTS_WITH("STARTS_WITH",
        Expression::startsWith,
        { lhs, rhs -> if (lhs is String && rhs is String) lhs.startsWith(rhs) else TODO() }
    ),
    NOT_STARTS_WITH(
        "NOT_STARTS_WITH",
        { lhs, rhs -> lhs.startsWith(rhs).not() },
        { lhs, rhs -> if (lhs is String && rhs is String) lhs.startsWith(rhs).not() else TODO() },
    ),

    ENDS_WITH("ENDS_WITH",
        Expression::endsWith,
        { lhs, rhs -> if (lhs is String && rhs is String) lhs.endsWith(rhs) else TODO() }
    ),
    NOT_ENDS_WITH(
        "NOT_ENDS_WITH",
        { lhs, rhs -> lhs.endsWith(rhs).not() },
        { lhs, rhs -> if (lhs is String && rhs is String) lhs.endsWith(rhs).not() else TODO() },
    ),

    IN("IN",
        Expression::`in`,
        { lhs, rhs -> if (lhs is Collection<*>) lhs.contains(rhs) else TODO() }
    ),
    NOT_IN(
        "NOT_IN",
        { lhs, rhs -> lhs.`in`(rhs).not() },
        { lhs, rhs -> if (lhs is Collection<*>) lhs.contains(rhs).not() else TODO() },
    ),

    INCLUDES(
        "INCLUDES",
        { lhs, rhs -> rhs.`in`(lhs) },
        { lhs, rhs -> if (rhs is Collection<*>) rhs.contains(lhs) else TODO() },
    ),
    NOT_INCLUDES(
        "NOT_INCLUDES",
        { lhs, rhs -> rhs.`in`(lhs).not() },
        { lhs, rhs -> if (rhs is Collection<*>) rhs.contains(lhs).not() else TODO() },
    );
}
