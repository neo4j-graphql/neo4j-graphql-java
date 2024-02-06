package org.neo4j.graphql.domain.predicates.definitions

import graphql.language.Type
import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.domain.fields.ScalarField

data class ScalarPredicateDefinition(
    override val name: String,
    val field: ScalarField,
    private val comparisonResolver: (Expression, Expression) -> Condition,
    val comparisonEvaluator: (Any?, Any?) -> Boolean,
    val type: Type<*>,
    val deprecated: String? = null
) : PredicateDefinition {
    fun createCondition(lhs: Expression, rhs: Expression): Condition = comparisonResolver(lhs, rhs)
}
