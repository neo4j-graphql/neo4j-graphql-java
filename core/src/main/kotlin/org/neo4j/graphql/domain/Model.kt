package org.neo4j.graphql.domain

data class Model(
    val nodes: List<Node>,
    val relationship: List<Relationship>
)
