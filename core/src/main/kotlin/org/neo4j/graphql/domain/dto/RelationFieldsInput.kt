package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField

class RelationFieldsInput(val node: Node, map: Map<String, Any?>) : Dict(map) {
    constructor(node: Node, input: Any?) : this(node, (input as? Map<*, *>)
        ?.mapKeys { (key, _) -> key as String }
        ?: error("expected a map for relation fields input"))

    fun getInputsPerField() = entries.mapNotNull { (fieldName, input) ->
        if (input == null) {
            return@mapNotNull null
        }
        val relField = node.getField(fieldName) as RelationField
        val refNodes = if (relField.isUnion) {
            UnionInput(input).mapNotNull { relField.getNode(it.key) }
        } else if (relField.isInterface) {
            relField.interfaze?.implementations ?: emptyList()
        } else {
            relField.node?.let { listOf(it) } ?: error("expect node, interface or union")
        }
        Triple(relField, refNodes, input)
    }
}

