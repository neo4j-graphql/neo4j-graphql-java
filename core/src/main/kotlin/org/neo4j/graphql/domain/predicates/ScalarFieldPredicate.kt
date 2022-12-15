package org.neo4j.graphql.domain.predicates

import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition

class ScalarFieldPredicate(
    val resolver: ScalarPredicateDefinition,
    val value: Any?
) : Predicate<ScalarField>(resolver.name, resolver.field)
