package org.neo4j.graphql.domain.predicates

import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition

class ScalarFieldPredicate(
    private val definition: ScalarPredicateDefinition,
    value: Any?
) : ExpressionPredicate(definition.name, definition::createCondition, value) {

    val field get() = definition.field
}
