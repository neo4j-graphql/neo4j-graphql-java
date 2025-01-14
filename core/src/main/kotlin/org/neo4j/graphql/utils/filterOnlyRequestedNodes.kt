package org.neo4j.graphql.utils

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.model.inputs.PerNodeInput
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere

fun filterOnlyRequestedNodes(
    nodes: Collection<Node>,
    perNodeWhere: PerNodeInput<*>?,
): Collection<Node> {
    if (perNodeWhere == null) {
        return nodes
    }
    return nodes.filter {
        when (val nodeWhere = perNodeWhere.getDataForNode(it)) {
            is WhereInput.NodeWhereInput -> true
            is ConnectionWhere.NodeConnectionWhere -> !nodeWhere.predicates.isNullOrEmpty()
            null -> false
            else -> TODO()
        }
    }
}
