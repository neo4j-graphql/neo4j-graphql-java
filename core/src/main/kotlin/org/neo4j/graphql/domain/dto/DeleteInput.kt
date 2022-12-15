package org.neo4j.graphql.domain.dto

class DeleteInput(input: Any?) : Dict(input) {
    val where: Map<String, Any>? by map.withDefault { null }
    val delete: Map<String, Any>? by map.withDefault { null }
}
