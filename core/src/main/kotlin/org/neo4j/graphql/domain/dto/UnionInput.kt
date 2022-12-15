package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.domain.Node

class UnionInput(input: Any?) : Dict(input) {
    fun getDataForNode(node: Node) = map[node.name]
}
