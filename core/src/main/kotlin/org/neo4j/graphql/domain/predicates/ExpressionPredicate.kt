package org.neo4j.graphql.domain.predicates

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Expression

open class ExpressionPredicate(
    val name: String,
    private val comparisonResolver: (Expression, Expression) -> Condition,
    val value: Any?,
) : Predicate(name) {
    fun createCondition(lhs: Expression, rhs: Expression): Condition = comparisonResolver(lhs, rhs)

}
