package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.nestedMap
import org.neo4j.graphql.wrapList

class UpdateInput(
    val relations: Map<RelationField, UpdateFieldInputs>? = null,
    val properties: ScalarProperties? = null,
    // for interfaces the update per implementation can be defined
    private val on: Map<Node, UpdateInput>? = null,
) {

    fun fields() = (relations?.keys ?: emptyList()) + (properties?.keys ?: emptyList())

    fun getAdditionalFieldsForImplementation(implementation: Node) = on?.get(implementation)

    fun getCommonInterfaceFields(implementation: Node): UpdateInput {
        return getAdditionalFieldsForImplementation(implementation)?.let { this.remove(it) } ?: this
    }

    private fun remove(other: UpdateInput): UpdateInput {
        return UpdateInput(
            relations?.toMutableMap()?.apply { keys.removeAll(other.relations?.keys ?: emptySet()) },
            properties?.toMutableMap()
                ?.apply { keys.removeAll(other.properties?.keys ?: emptySet()) }
                ?.let { ScalarProperties(it) }
        )
    }

    companion object {
        fun create(node: Node, data: Map<String, *>) {
            data.mapValues { (key, value) ->
                val field = node.getField(key)
                val mappedValue = if (field is RelationField) {
                    value
                } else {
                    value
                }
                field to mappedValue
            }
        }

        fun create(implementingType: ImplementingType, data: Any?): UpdateInput? {
            val map = (data as? Map<*, *>) ?: return null
            val relations = mutableMapOf<RelationField, UpdateFieldInputs>()
            val properties = mutableMapOf<ScalarField, Any?>()
            val on = parseOn(implementingType, data)

            map.forEach { (key, value) ->
                if (key == Constants.ON) return@forEach
                val field = implementingType.getField(key as String) ?: return@forEach
                if (field is RelationField) {
                    UpdateFieldInputs
                        .create(field, requireNotNull(value))
                        ?.let { relations[field] = it }
                } else if (field is ScalarField) {
                    properties[field] = value
                }
            }
            return UpdateInput(
                relations.takeIf { it.isNotEmpty() },
                properties.takeIf { it.isNotEmpty() }?.let { ScalarProperties(it) },
                on
            )
        }

        private fun parseOn(type: ImplementingType, data: Any?): PerNodeInput<UpdateInput>? {
            if (type !is Interface) {
                throw IllegalArgumentException("_on is only supported on interfaces")
            }
            return PerNodeInput.create(type, data, { impl, value -> create(impl, value) })
        }


        fun create(field: RelationField, data: Any): UpdateInput? = field.extractOnTarget(
            onImplementingType = { create(it, data) },
            onUnion = { TODO("union") },
        )
    }

}
