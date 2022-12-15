package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.fields.ScalarField

class UniqueWhere(val map: Map<ScalarField, Any?>) : Map<ScalarField, Any?> by map {

    companion object {
        fun create(type: ImplementingType, data: Map<String, *>) = data
            .map { (key, value) ->
                val field = type.getField(key) as? ScalarField
                    ?: TODO("unsupported field $key")
                field to value
            }
            .toMap()
            .takeIf { it.isNotEmpty() }
            ?.let { UniqueWhere(it) }
    }
}
