package org.neo4j.graphql.domain.inputs.connect_or_create

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.RelationFieldsInput

class ConnectOrCreateInput private constructor(
    node: Node,
    data: Dict
) : RelationFieldsInput<ConnectOrCreateFieldInput>(
    node,
    data,
    { field, value -> ConnectOrCreateFieldInput.create(field, value) }
) {
    companion object {
        fun create(node: Node, anyData: Any) = ConnectOrCreateInput(node, Dict(anyData))
    }
}
