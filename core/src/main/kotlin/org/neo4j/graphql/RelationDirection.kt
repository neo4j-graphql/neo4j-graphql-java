package org.neo4j.graphql

enum class RelationDirection {
    IN,
    OUT,
    BOTH;

    fun invert(): RelationDirection = when (this) {
        IN -> OUT
        OUT -> IN
        else -> this
    }

}
