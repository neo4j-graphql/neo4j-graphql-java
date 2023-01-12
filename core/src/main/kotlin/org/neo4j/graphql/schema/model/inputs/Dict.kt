package org.neo4j.graphql.schema.model.inputs

open class Dict(val map: Map<String, Any?>) : Map<String, Any?> by map {
    constructor(input: Any?) : this((input as? Map<*, *>)
        ?.mapKeys { (key, _) -> key as String }
        ?: error("expected a map with string keys"))
}

