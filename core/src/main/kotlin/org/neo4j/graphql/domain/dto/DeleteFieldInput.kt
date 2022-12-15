package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.domain.fields.RelationField

data class DeleteFieldInput(
    val delete: DeleteInput,
    val where: ConnectionWhere? = null
) {
    companion object {

        fun create(field: RelationField, it: Any) : DeleteFieldInput{
            TODO("Not yet implemented")
        }
    }
}
