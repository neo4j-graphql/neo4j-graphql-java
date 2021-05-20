package org.neo4j.graphql

class DirectiveConstants {

    companion object {

        const val IGNORE = "ignore"
        const val RELATION = "relation"
        const val RELATION_NAME = "name"
        const val RELATION_DIRECTION = "direction"
        const val RELATION_FROM = "from"
        const val RELATION_TO = "to"

        const val CYPHER = "cypher"
        const val CYPHER_STATEMENT = "statement"
        const val CYPHER_PASS_THROUGH = "passThrough"

        const val PROPERTY = "property"
        const val PROPERTY_NAME = "name"

        const val DYNAMIC = "dynamic"
        const val DYNAMIC_PREFIX = "prefix"
    }
}
