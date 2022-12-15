package org.neo4j.graphql.domain.predicates.definitions

import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.predicates.RelationOperator

data class RelationPredicateDefinition(
    override val name: String,
    val field: RelationField,
    val operator: RelationOperator,
    val connection: Boolean
) : PredicateDefinition
