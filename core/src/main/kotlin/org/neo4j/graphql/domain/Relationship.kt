package org.neo4j.graphql.domain

// TODO can we move all this to the relationField?
class Relationship(
    // TODO is this required here?
    /**
     * The name of the GraphQl Type
     */
    val name: String,
    /**
     * The type of the neo4j relation
     */
    val type: String,
    direction: Direction,
    val description: String?,
    val properties: RelationshipProperties?,
) {
    enum class Direction {
        IN, OUT
    }
}
