package org.neo4j.graphql.domain.inputs.connect

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.*
import org.neo4j.graphql.wrapList

sealed interface ConnectFieldInput {

    sealed class ImplementingTypeConnectFieldInput(
        implementingType: ImplementingType,
        field: RelationField,
        data: Dict
    ) {
        val edge = data[Constants.EDGE_FIELD]?.let { ScalarProperties.create(data, implementingType) }
        val where = data[Constants.WHERE]?.let { ConnectWhere(implementingType, Dict(it)) }
        val connect = data[Constants.CONNECT_FIELD]?.let { it.wrapList().map { ConnectInput.create(field, it)} }
    }

    class NodeConnectFieldInput(node: Node, field: RelationField, data: Dict) :
        ImplementingTypeConnectFieldInput(node, field, data)

    class InterfaceConnectFieldInput(interfaze: Interface, field: RelationField, data: Dict) :
        ImplementingTypeConnectFieldInput(interfaze, field, data) {
        val on = data[Constants.ON]?.let {
            PerNodeInput(
                interfaze,
                Dict(it),
                { node: Node, value: Any -> NodeConnectFieldInputs.create(node, field, value) }
            )
        }
    }

    class NodeConnectFieldInputs(items: List<NodeConnectFieldInput>) : ConnectFieldInput,
        InputListWrapper<NodeConnectFieldInput>(items) {

        companion object {
            fun create(node: Node, field: RelationField, value: Any?) = create(
                value,
                ::NodeConnectFieldInputs,
                { NodeConnectFieldInput(node, field, Dict(it)) }
            )
        }
    }

    class InterfaceConnectFieldInputs(items: List<InterfaceConnectFieldInput>) : ConnectFieldInput,
        InputListWrapper<InterfaceConnectFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, field: RelationField, value: Any?) = create(
                value,
                ::InterfaceConnectFieldInputs,
                { InterfaceConnectFieldInput(interfaze, field, Dict(it)) }
            )
        }
    }

    class UnionConnectFieldInput(union: Union, field: RelationField, data: Dict) : ConnectFieldInput,
        PerNodeInput<NodeConnectFieldInputs>(
            union,
            data,
            { node, value -> NodeConnectFieldInputs.create(node, field, value) }
        )

    class ConnectWhere(type: ImplementingType, data: Dict) {
        val node = data[Constants.NODE_FIELD]?.let { WhereInput.create(type, it) }
    }

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeConnectFieldInputs.create(it, field, value) },
            onInterface = { InterfaceConnectFieldInputs.create(it, field, value) },
            onUnion = { UnionConnectFieldInput(it, field, Dict(value)) }
        )
    }
}
