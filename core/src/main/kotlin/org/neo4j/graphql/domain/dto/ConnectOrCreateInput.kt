package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.nestedMap

data class ConnectOrCreateInput(
    val where: ConnectOrCreateWhereInput?,
    val onCreate: OnCreateInput?,
) {

    companion object {
        fun create(field: RelationField, targetType : ImplementingType,  data: Any): ConnectOrCreateInput {
            return ConnectOrCreateInput(
                where = data.nestedMap(Constants.WHERE)?.let { ConnectOrCreateWhereInput.create(targetType, it) },
                onCreate = data.nestedMap(Constants.ON_CREATE_FIELD)
                    ?.let { OnCreateInput.create(targetType, field.properties, it) },
            )
        }

    }
}
