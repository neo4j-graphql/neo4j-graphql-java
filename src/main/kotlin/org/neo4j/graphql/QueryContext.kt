package org.neo4j.graphql

data class QueryContext @JvmOverloads constructor(
        val topLevelWhere: Boolean = true
)