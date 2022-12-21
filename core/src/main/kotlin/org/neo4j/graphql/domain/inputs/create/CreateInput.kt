package org.neo4j.graphql.domain.inputs.create

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.RelationFieldsInput
import org.neo4j.graphql.domain.inputs.ScalarProperties

class CreateInput private constructor(
    node: Node,
    data: Dict
) : RelationFieldsInput<CreateFieldInput>(
    node,
    data,
    { field, value -> CreateFieldInput.create(field, value) }
) {

    val properties by lazy { ScalarProperties.create(data, node) }

    companion object {
        fun create(node: Node, anyData: Any) = CreateInput(node, Dict(anyData))
    }
}

