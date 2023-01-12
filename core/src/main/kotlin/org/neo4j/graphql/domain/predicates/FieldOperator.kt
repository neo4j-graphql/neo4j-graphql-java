package org.neo4j.graphql.domain.predicates

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression

enum class FieldOperator(
    val suffix: String,
    val conditionCreator: (Expression, Expression) -> Condition
) {
    EQUAL("", { lhs, rhs -> if (rhs == Cypher.literalNull()) lhs.isNull else lhs.eq(rhs) }),
    NOT_EQUAL("NOT", { lhs, rhs -> if (rhs == Cypher.literalNull()) lhs.isNotNull else lhs.eq(rhs).not() }),
    LT("LT", Expression::lt),
    LTE("LTE", Expression::lte),
    GT("GT", Expression::gt),
    GTE("GTE", Expression::gte),

    MATCHES("MATCHES", Expression::matches),

    CONTAINS("CONTAINS", Expression::contains),
    NOT_CONTAINS("NOT_CONTAINS", { lhs, rhs -> lhs.contains(rhs).not() }),

    STARTS_WITH("STARTS_WITH", Expression::startsWith),
    NOT_STARTS_WITH("NOT_STARTS_WITH", { lhs, rhs -> lhs.startsWith(rhs).not() }),

    ENDS_WITH("ENDS_WITH", Expression::endsWith),
    NOT_ENDS_WITH("NOT_ENDS_WITH", { lhs, rhs -> lhs.endsWith(rhs).not() }),

    IN("IN", Expression::`in`),
    NOT_IN("NOT_IN", { lhs, rhs -> lhs.`in`(rhs).not() }),

    INCLUDES("INCLUDES", { lhs, rhs -> rhs.`in`(lhs) }),
    NOT_INCLUDES("NOT_INCLUDES", { lhs, rhs -> rhs.`in`(lhs).not() }),
}
