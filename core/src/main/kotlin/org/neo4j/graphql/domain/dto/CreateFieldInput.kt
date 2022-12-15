package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.nestedMap
import org.neo4j.graphql.nestedObject

data class CreateFieldInput(
    val node: CreateInput?,
    val edge: ScalarProperties?,
) {
    companion object {
        fun create(field: RelationField, type: ImplementingType, data: Any): CreateFieldInput {
            val node = data.nestedObject(Constants.NODE_FIELD)?.let { CreateInput.create(type, it) }
            val edge = data.nestedMap(Constants.EDGE_FIELD)?.let { ScalarProperties.create(it, field.properties) }
            return CreateFieldInput(node, edge)
        }
    }
}
