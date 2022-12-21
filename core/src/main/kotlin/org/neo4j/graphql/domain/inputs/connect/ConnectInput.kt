package org.neo4j.graphql.domain.inputs.connect

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.PerNodeInput.Companion.getCommonFields
import org.neo4j.graphql.domain.inputs.RelationFieldsInput
import org.neo4j.graphql.wrapList

sealed class ConnectInput private constructor(
    implementingType: ImplementingType,
    data: Dict
) : RelationFieldsInput<ConnectFieldInput>(
    implementingType,
    data,
    { field, value -> ConnectFieldInput.create(field, value) }
) {

    class NodeConnectInput(node: Node, data: Dict) : ConnectInput(node, data)

    class InterfaceConnectInput(interfaze: Interface, data: Dict) : ConnectInput(interfaze, data) {

        val on = data[Constants.ON]?.let {
            PerNodeInput(
                interfaze,
                Dict(it),
                { node, value -> value.wrapList().map { NodeConnectInput(node, Dict(it)) } }
            )
        }

        fun getCommonFields(implementation: Node) = on.getCommonFields(implementation, data, ::NodeConnectInput)
    }

    companion object {
        fun create(implementingType: ImplementingType, anyData: Any) = when (implementingType) {
            is Node -> NodeConnectInput(implementingType, Dict(anyData))
            is Interface -> InterfaceConnectInput(implementingType, Dict(anyData))
        }

        fun create(relationField: RelationField, anyData: Any): ConnectInput = relationField.extractOnTarget(
            onImplementingType = { create(it, anyData) },
            onUnion = { error("cannot create for a union type of field ${relationField.getOwnerName()}.${relationField.fieldName}") }
        )
    }
}

