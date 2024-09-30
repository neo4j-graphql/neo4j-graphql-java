package org.neo4j.graphql.domain.predicates

import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere

class ConnectionFieldPredicate(
    val def: RelationPredicateDefinition,
    val where: ConnectionWhere?,
) : Predicate(def.name) {
    val field get() = def.field.connectionField
}
