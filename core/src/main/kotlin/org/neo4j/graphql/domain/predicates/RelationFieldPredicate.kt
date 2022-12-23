package org.neo4j.graphql.domain.predicates

import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition

class RelationFieldPredicate(
    val def: RelationPredicateDefinition,
    val where: WhereInput?,
) : Predicate(def.name) {
    val field get() = def.field
}
