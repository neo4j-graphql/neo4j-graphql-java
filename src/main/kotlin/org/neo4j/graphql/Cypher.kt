package org.neo4j.graphql

data class Cypher(val query: String, val params: Map<String, Any?> = emptyMap()) {
    fun with(p: Map<String, Any?>) = this.copy(params = this.params + p)
    fun escapedQuery() = query.replace("\"", "\\\"").replace("'", "\\'")

    companion object {
        val EMPTY = Cypher("")
    }
}