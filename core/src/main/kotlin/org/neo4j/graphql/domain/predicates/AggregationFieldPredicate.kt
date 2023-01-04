package org.neo4j.graphql.domain.predicates

import org.neo4j.graphql.domain.predicates.definitions.AggregationPredicateDefinition

class AggregationFieldPredicate(
    val resolver: AggregationPredicateDefinition,
    val value: Any?
) : Predicate(resolver.name) {
    val field get() = resolver.field
}
