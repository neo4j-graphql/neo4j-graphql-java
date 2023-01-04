package org.neo4j.graphql.domain.inputs.field_arguments

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput

class AggregateFieldInputArgs(node: Node, data: Map<String, *>) {
    val where = data[Constants.WHERE]?.let { WhereInput.NodeWhereInput(node, Dict(it)) }
    val directed = data[Constants.DIRECTED] as Boolean?
}
