package org.neo4j.graphql.schema.model.inputs

import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.fields.RelationField


abstract class RelationFieldsInput<T> internal constructor(
    implementingType: ImplementingType,
    protected val data: Dict,
    fieldFactory: (field: RelationField, value: Any) -> T?
) {

    val relations = initRelations(implementingType, data, fieldFactory)

    companion object {

        private fun <T> initRelations(
            implementingType: ImplementingType,
            data: Map<String, Any?>,
            fieldFactory: (field: RelationField, value: Any) -> T?
        ): Map<RelationField, T> {
            val relations: MutableMap<RelationField, T> = mutableMapOf()
            data.forEach { (fieldName, value) ->
                if (value == null) return@forEach
                val field = implementingType.getField(fieldName) as? RelationField ?: return@forEach
                fieldFactory(field, value)
                    ?.let { relations[field] = it }
            }
            return relations.toMap()
        }
    }
}

