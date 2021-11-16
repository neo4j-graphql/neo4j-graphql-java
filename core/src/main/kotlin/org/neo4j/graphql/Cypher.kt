package org.neo4j.graphql

import graphql.schema.GraphQLType

data class Cypher @JvmOverloads constructor(val query: String, val params: Map<String, Any?> = emptyMap(), var type: GraphQLType? = null, val variable: String) {
    fun with(p: Map<String, Any?>) = this.copy(params = this.params + p)
}
