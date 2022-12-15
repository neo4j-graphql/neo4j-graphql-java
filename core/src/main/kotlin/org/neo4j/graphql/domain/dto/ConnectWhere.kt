package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.nestedMap

data class ConnectWhere(
    val node: WhereInput.FieldContainerWhereInput
) {
    companion object {

        fun create(type: ImplementingType, data: Any?): ConnectWhere? = data.nestedMap(Constants.NODE_FIELD)
            ?.let { WhereInput.FieldContainerWhereInput.create(type, it) }
            ?.let { ConnectWhere(it) }

    }
}
