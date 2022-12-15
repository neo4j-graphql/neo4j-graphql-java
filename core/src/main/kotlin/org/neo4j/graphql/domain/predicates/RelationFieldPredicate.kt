package org.neo4j.graphql.domain.predicates

import org.neo4j.graphql.domain.dto.WhereInput
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition

class RelationFieldPredicate(
    val def: RelationPredicateDefinition,
    val where: WhereInput?,
) : Predicate<RelationField>(def.name, def.field)
