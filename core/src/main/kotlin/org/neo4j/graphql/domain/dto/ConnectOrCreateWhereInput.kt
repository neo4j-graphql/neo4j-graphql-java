package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.nestedMap

data class ConnectOrCreateWhereInput(
    val node: UniqueWhere?
) {
    companion object {
        fun create(type: ImplementingType, map: Map<String, *>): ConnectOrCreateWhereInput? {
            return map.nestedMap(Constants.NODE_FIELD)
                ?.let { UniqueWhere.create(type, it) }
                ?.let { ConnectOrCreateWhereInput(it) }
        }
    }
}
