package org.neo4j.graphql.domain.predicates

import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition

class ConnectionFieldPredicate(
    val def: RelationPredicateDefinition,
    val where: ConnectionWhere?,
) : Predicate(def.name) {
    val field get() = def.field.connectionField
}