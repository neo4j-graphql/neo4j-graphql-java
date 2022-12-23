package org.neo4j.graphql.domain.predicates

import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition

class ScalarFieldPredicate(
    private val resolver: ScalarPredicateDefinition,
    value: Any?
) : ExpressionPredicate(resolver.name, { rhs, lhs -> resolver.createCondition(rhs, lhs) }, value) {
    val field get() = resolver.field
}
