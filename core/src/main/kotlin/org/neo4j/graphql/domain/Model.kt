package org.neo4j.graphql.domain

import org.neo4j.graphql.domain.fields.RelationField

data class Model(
    val nodes: List<Node>,
    val relationship: List<RelationField>
)
