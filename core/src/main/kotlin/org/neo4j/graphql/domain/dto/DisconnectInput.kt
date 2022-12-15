package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.nestedMap
import org.neo4j.graphql.wrapList

class DisconnectInput(
    val relations: Map<RelationField, DisconnectFieldInput>? = null,
    val on: PerNodeInput<List<DisconnectInput>>? = null,
) {

    fun getAdditionalFieldsForImplementation(implementation: Node) = on?.get(implementation)

    fun getCommonInterfaceFields(implementation: Node): DisconnectInput {
        return getAdditionalFieldsForImplementation(implementation)?.let { this.remove(it) } ?: this
    }

    private fun remove(other: List<DisconnectInput>): DisconnectInput {
        return DisconnectInput(
            relations
                ?.toMutableMap()
                ?.apply {
                    val fieldsToRemove = other.flatMapTo(hashSetOf()) { it.relations?.keys ?: emptySet() }
                    keys.removeAll(fieldsToRemove)
                }
        )
    }

    companion object {
        fun create(type: ImplementingType, data: Any): DisconnectInput? {
            if (data !is Map<*, *>) return null
            val on = data.nestedMap(Constants.ON)?.let {
                parseOn(type, it)
            }
            val relations = data.mapNotNull { (key, value) ->
                if (key == Constants.ON) return@mapNotNull null
                val field = type.getField(key as String) as? RelationField
                    ?: throw IllegalArgumentException("only realtional fields are allowed for DisconnectInput")
                val input = DisconnectFieldInput.create(field, value) ?: return@mapNotNull null
                field to input
            }
                .takeIf { it.isNotEmpty() }
                ?.toMap()
            return DisconnectInput(relations, on)
        }

        private fun parseOn(type: ImplementingType, data: Map<String, *>): PerNodeInput<List<DisconnectInput>>? {
            if (type !is Interface) {
                throw IllegalArgumentException("_on is only supported on interfaces")
            }
            return PerNodeInput.create(type, data, { impl, value ->
                value
                    ?.wrapList()
                    ?.mapNotNull { create(impl, it) }
                    ?.takeIf { it.isNotEmpty() }
            })
        }

    }
}
