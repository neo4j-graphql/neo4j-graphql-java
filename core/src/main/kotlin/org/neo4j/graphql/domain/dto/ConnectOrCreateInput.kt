package org.neo4j.graphql.domain.dto

class ConnectOrCreateInput(input: Any?) : Dict(input) {
    val where: Map<String, Any>? by map.withDefault { null }
    val onCreate: Map<String, Any>? by map.withDefault { null }
}
