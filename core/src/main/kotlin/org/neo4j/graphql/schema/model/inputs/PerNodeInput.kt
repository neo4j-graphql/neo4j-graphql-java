package org.neo4j.graphql.schema.model.inputs

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.NodeResolver

open class PerNodeInput<T>(
    private val nodeResolver: NodeResolver,
    internal val data: Dict,
    private val convertValue: (node: Node, value: Any) -> T
) {

    val dataPerNode = data.entries.mapNotNull { (name, value) ->
        if (value == null) return@mapNotNull null
        val node = nodeResolver.getRequiredNode(name)
        val convertedValue = convertValue(node, value) ?: return@mapNotNull null
        node to convertedValue
    }.toMap()

    fun getDataForNode(node: Node): T? = dataPerNode[node]
}
