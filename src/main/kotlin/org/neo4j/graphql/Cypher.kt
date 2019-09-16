package org.neo4j.graphql

import graphql.language.Type

data class Cypher @JvmOverloads constructor(val query: String, val params: Map<String, Any?> = emptyMap(), var type: Type<*>? = null) {
    fun with(p: Map<String, Any?>) = this.copy(params = this.params + p)
    fun escapedQuery() = query.replace("\"", "\\\"").replace("'", "\\'")

    companion object {
        val EMPTY = Cypher("")
    }
}