package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.ScalarField

class ScalarProperties(data: Map<ScalarField, Any?>) : Map<ScalarField, Any?> by data {
    companion object {

        fun create(data: Map<String, *>, fieldContainer: FieldContainer<in ScalarField>?): ScalarProperties? {
            if (fieldContainer == null) return null
            return data
                .mapNotNull { (key, value) ->
                    val field = fieldContainer.getField(key) as? ScalarField
                        ?: TODO("is wrong data requested")
                    field to value
                }
                .let { ScalarProperties(it.toMap()) }
        }
    }
}
