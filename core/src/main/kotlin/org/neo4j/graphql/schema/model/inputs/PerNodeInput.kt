package org.neo4j.graphql.schema.model.inputs

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.NodeResolver
import org.neo4j.graphql.wrapList

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

    fun hasNodeData(node: Node) = dataPerNode.containsKey(node)

    fun getDataForNode(node: Node): T? = dataPerNode[node]

    companion object {
        fun <NT> (PerNodeInput<*>?).getCommonFields(
            node: Node,
            commonData: Dict,
            factory: (node: Node, data: Dict) -> NT
        ): NT {
            val perNodeData = this?.data?.get(node.name) ?: return factory(node, commonData)

            val commonDataExcludingOverrides = commonData
                .toMutableMap()
                .apply {
                    val fieldsToRemove = perNodeData.wrapList().flatMapTo(hashSetOf()) { Dict(it).keys }
                    keys.removeAll(fieldsToRemove)
                }

            return factory(node, Dict(commonDataExcludingOverrides))
        }
    }
}
