package org.neo4j.graphql.domain.predicates

import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition
import org.neo4j.graphql.schema.model.inputs.WhereInput

class RelationFieldPredicate(
    val def: RelationPredicateDefinition,
    val where: WhereInput?,
) : Predicate(def.name) {
    val field get() = def.field
}
