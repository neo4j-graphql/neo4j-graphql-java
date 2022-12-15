package org.neo4j.graphql.domain.predicates

import org.neo4j.graphql.domain.fields.BaseField

abstract class Predicate<F : BaseField>(
    val key: String,
    val field: F
)
