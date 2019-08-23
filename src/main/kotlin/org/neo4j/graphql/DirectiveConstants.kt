package org.neo4j.graphql

class DirectiveConstants {
    companion object {
        const val RELATION = "relation"
        const val RELATION_NAME = "name"
        const val RELATION_DIRECTION = "direction"
        const val RELATION_DIRECTION_IN = "IN"
        const val RELATION_DIRECTION_OUT = "OUT"
        const val RELATION_DIRECTION_BOTH = "BOTH"
        const val RELATION_FROM = "from"
        const val RELATION_TO = "to"

        const val CYPHER = "cypher"
        const val CYPHER_STATEMENT = "statement"

        const val PROPERTY = "property"
        const val PROPERTY_NAME = "name"
    }
}