package org.neo4j.graphql.domain.inputs.create

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.RelationFieldsInput

class RelationInput private constructor(
    node: Node,
    data: Dict
) : RelationFieldsInput<RelationFieldInput>(
    node,
    data,
    { field, value -> RelationFieldInput.create(field, value) }) {

    companion object {
        fun create(node: Node, anyData: Any) = RelationInput(node, Dict(anyData))
    }
}

