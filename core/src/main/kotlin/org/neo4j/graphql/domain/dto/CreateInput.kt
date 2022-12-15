package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants

class CreateInput(input: Any?) : Dict(input) {

    constructor(node: Map<String, Any?>?, edge: Map<String, Any?>?) : this(
        mapOf(
            Constants.NODE_FIELD to node,
            Constants.EDGE_FIELD to edge
        )
    )

    val node: Map<String, Any?>? by map.withDefault { null }
    val edge: Map<String, Any?>? by map.withDefault { null }
}
