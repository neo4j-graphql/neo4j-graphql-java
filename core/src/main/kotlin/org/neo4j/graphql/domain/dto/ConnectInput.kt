package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.nestedObject

class ConnectInput(
    val on: PerNodeInput<List<ConnectInput>>? = null,
    data: Map<RelationField, ConnectFieldInput>
) : Map<RelationField, ConnectFieldInput> by data {

    companion object {
        fun create(type: ImplementingType, data: Any): ConnectInput? {
//            data.nestedObject(Constants.ON)?.let {
//                PerNodeInput.create() }
//            val inputs = mutableMapOf<RelationField, ConnectFieldInput>()
//            (data as Map<*, *>).forEach { (key, value) ->
//                val field = type.getField(key as String) as? RelationField
//                    ?: throw IllegalArgumentException("require realtion field")
//                inputs[field] = ConnectFieldInput.create(field, value)
//            }
            TODO()
        }
    }
}
