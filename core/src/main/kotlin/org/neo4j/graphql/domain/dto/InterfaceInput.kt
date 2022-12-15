package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.domain.Node

class InterfaceInput(input: Any?) : Dict(input) {
    fun getDataForNode(node: Node) = map[node.name]
    fun getNodeInputForNode(node: Node) = getDataForNode(node)?.let { NodeInput(it) }
}
