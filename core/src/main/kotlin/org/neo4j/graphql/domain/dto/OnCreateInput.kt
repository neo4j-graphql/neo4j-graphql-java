package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.nestedMap

data class OnCreateInput(
    val node: ScalarProperties?,
    val edge: ScalarProperties?,
) {
    companion object {
        fun create(
            implementingType: ImplementingType,
            edge: RelationshipProperties?,
            map: Map<String, *>
        ): OnCreateInput {
            return OnCreateInput(
                node = map.nestedMap(Constants.NODE_FIELD)?.let { ScalarProperties.create(it, implementingType) },
                edge = map.nestedMap(Constants.EDGE_FIELD)?.let { ScalarProperties.create(it, edge) },
            )
        }

    }
}
