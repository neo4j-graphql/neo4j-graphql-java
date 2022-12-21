package org.neo4j.graphql.domain.inputs.connect

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.*
import org.neo4j.graphql.wrapList

sealed interface ConnectFieldInput {

    sealed class ImplementingTypeConnectFieldInput(
        implementingType: ImplementingType,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) {
        val edge = relationshipProperties
            ?.let { props -> data[Constants.EDGE_FIELD]?.let { ScalarProperties.create(data, props) } }

        val where = data[Constants.WHERE]?.let { ConnectWhere(implementingType, Dict(it)) }
        val connect =
            data[Constants.CONNECT_FIELD]?.let { input ->
                input.wrapList().map { ConnectInput.create(implementingType, it) }
            }
    }

    class NodeConnectFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeConnectFieldInput(node, relationshipProperties, data)

    class InterfaceConnectFieldInput(
        interfaze: Interface,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) :
        ImplementingTypeConnectFieldInput(interfaze, relationshipProperties, data) {
        val on = data[Constants.ON]?.let {
            PerNodeInput(
                interfaze,
                Dict(it),
                { node: Node, value: Any -> NodeConnectFieldInputs.create(node, relationshipProperties, value) }
            )
        }
    }

    class NodeConnectFieldInputs(items: List<NodeConnectFieldInput>) : ConnectFieldInput,
        InputListWrapper<NodeConnectFieldInput>(items) {

        companion object {
            fun create(node: Node, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::NodeConnectFieldInputs,
                { NodeConnectFieldInput(node, relationshipProperties, Dict(it)) }
            )
        }
    }

    class InterfaceConnectFieldInputs(items: List<InterfaceConnectFieldInput>) : ConnectFieldInput,
        InputListWrapper<InterfaceConnectFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::InterfaceConnectFieldInputs,
                { InterfaceConnectFieldInput(interfaze, relationshipProperties, Dict(it)) }
            )
        }
    }

    class UnionConnectFieldInput(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) :
        ConnectFieldInput,
        PerNodeInput<NodeConnectFieldInputs>(
            union,
            data,
            { node, value -> NodeConnectFieldInputs.create(node, relationshipProperties, value) }
        )

    class ConnectWhere(type: ImplementingType, data: Dict) {
        val node = data[Constants.NODE_FIELD]?.let { WhereInput.create(type, it) }
    }

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeConnectFieldInputs.create(it, field.properties, value) },
            onInterface = { InterfaceConnectFieldInputs.create(it, field.properties, value) },
            onUnion = { UnionConnectFieldInput(it, field.properties, Dict(value)) }
        )
    }
}
