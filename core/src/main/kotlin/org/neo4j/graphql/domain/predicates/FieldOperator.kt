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
    STARTS_WITH("STARTS_WITH",
        Expression::startsWith,
        { lhs, rhs -> if (lhs is String && rhs is String) lhs.startsWith(rhs) else TODO() }
    ),
    ENDS_WITH("ENDS_WITH",
        Expression::endsWith,
        { lhs, rhs -> if (lhs is String && rhs is String) lhs.endsWith(rhs) else TODO() }
    ),

    IN("IN",
        Expression::`in`,
        { lhs, rhs -> if (lhs is Collection<*>) lhs.contains(rhs) else TODO() }
    ),
    INCLUDES(
        "INCLUDES",
        { lhs, rhs -> rhs.`in`(lhs) },
        { lhs, rhs -> if (rhs is Collection<*>) rhs.contains(lhs) else TODO() },
    ),
}
