package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.nestedMap
import org.neo4j.graphql.nestedObject

data class UpdateConnectionInput(
    val node: UpdateInput? = null,
    val edge: ScalarProperties? = null
) {
    companion object {
        fun create(field: RelationField, value: Any): UpdateConnectionInput {
            return UpdateConnectionInput(
                node = value.nestedObject(Constants.NODE_FIELD)?.let { UpdateInput.create(field, it) },
                edge = value.nestedMap(Constants.EDGE_FIELD)?.let { ScalarProperties.create(it, field.properties) }
            )
        }
    }
}
