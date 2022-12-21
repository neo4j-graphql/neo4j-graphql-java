package org.neo4j.graphql.domain.inputs.update

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.PerNodeInput.Companion.getCommonFields
import org.neo4j.graphql.domain.inputs.RelationFieldsInput
import org.neo4j.graphql.domain.inputs.ScalarProperties

sealed class UpdateInput private constructor(implementingType: ImplementingType, data: Dict) :
    RelationFieldsInput<UpdateFieldInput>(
        implementingType,
        data,
        { field, value -> UpdateFieldInput.create(field, value) }
    ) {


    val properties by lazy { ScalarProperties.create(data, implementingType) }

    class NodeUpdateInput(node: Node, data: Dict) : UpdateInput(node, data)

    class InterfaceUpdateInput(interfaze: Interface, data: Dict) : UpdateInput(interfaze, data) {

        val on = data[Constants.ON]?.let {
            PerNodeInput(interfaze, Dict(it), { node: Node, value: Any -> NodeUpdateInput(node, Dict(value)) })
        }

        fun getCommonFields(implementation: Node) = on.getCommonFields(implementation, data, ::NodeUpdateInput)
    }

    companion object {
        fun create(implementingType: ImplementingType, anyData: Any) = when (implementingType) {
            is Node -> NodeUpdateInput(implementingType, Dict(anyData))
            is Interface -> InterfaceUpdateInput(implementingType, Dict(anyData))
        }
    }
}

