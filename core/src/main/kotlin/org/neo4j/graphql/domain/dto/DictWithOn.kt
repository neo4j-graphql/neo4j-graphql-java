package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Node

class DictWithOn(input: Any?) : Dict(input) {

    @Suppress("PropertyName")
    val _on: Any? by map.withDefault { null }

   private fun getOnForNode(node: Node): List<Dict> {
        if (_on == null) return emptyList()
        return Dict(_on)[node.name]
            ?.let { it as? List<*> ?: listOf(it) }
            ?.mapNotNull { it?.let { Dict(it) } }
            ?: emptyList()
    }
    fun getFields(node: Node):  Pair<List<Dict>, Dict> {
        if (_on == null) return emptyList<Dict>() to this
        val onList = getOnForNode(node)
        return onList to Dict(filterKeys { key -> key != Constants.ON && onList.find { it.containsKey(key) } == null })
    }

}
