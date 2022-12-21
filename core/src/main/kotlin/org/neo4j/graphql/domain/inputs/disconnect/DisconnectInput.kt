package org.neo4j.graphql.domain.inputs.disconnect

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.PerNodeInput.Companion.getCommonFields
import org.neo4j.graphql.domain.inputs.RelationFieldsInput
import org.neo4j.graphql.wrapList

sealed class DisconnectInput private constructor(implementingType: ImplementingType, data: Dict) :
    RelationFieldsInput<DisconnectFieldInput>(
        implementingType,
        data,
        { field, value -> DisconnectFieldInput.create(field, value) }
    ) {

    class NodeDisconnectInput(node: Node, data: Dict) : DisconnectInput(node, data)

    class InterfaceDisconnectInput(interfaze: Interface, data: Dict) : DisconnectInput(interfaze, data) {

        val on = data[Constants.ON]?.let {
            PerNodeInput(
                interfaze,
                Dict(it),
                { node: Node, value: Any -> value.wrapList().map { NodeDisconnectInput(node, Dict(it)) } }
            )
        }

        fun getCommonFields(implementation: Node) = on.getCommonFields(implementation, data, ::NodeDisconnectInput)
    }

    companion object {
        fun create(implementingType: ImplementingType, anyData: Any) = when (implementingType) {
            is Node -> NodeDisconnectInput(implementingType, Dict(anyData))
            is Interface -> InterfaceDisconnectInput(implementingType, Dict(anyData))
        }
    }
}

