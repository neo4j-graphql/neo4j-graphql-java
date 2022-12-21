package org.neo4j.graphql.domain.inputs.disconnect

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.InputListWrapper
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere
import org.neo4j.graphql.wrapList

sealed interface DisconnectFieldInput {

    sealed interface ImplementingTypeDisconnectFieldInput {
        val where: ConnectionWhere?
        val disconnect: List<DisconnectInput>?
    }

    class NodeDisconnectFieldInput(node: Node, field: RelationField, data: Dict) :
        ImplementingTypeDisconnectFieldInput {

        override val where = data[Constants.WHERE]?.let {
            ConnectionWhere.NodeConnectionWhere.create(node, field, Dict(it))
        }

        override val disconnect = data[Constants.DISCONNECT_FIELD]
            ?.wrapList()?.map { DisconnectInput.NodeDisconnectInput(node, Dict(it)) }
    }

    class InterfaceDisconnectFieldInput(interfaze: Interface, field: RelationField, data: Dict) :
        ImplementingTypeDisconnectFieldInput {

        override val where = data[Constants.WHERE]?.let {
            ConnectionWhere.InterfaceConnectionWhere.create(interfaze, field, Dict(it))
        }

        override val disconnect = data[Constants.DISCONNECT_FIELD]
            ?.wrapList()?.map { DisconnectInput.InterfaceDisconnectInput(interfaze, Dict(it)) }

        val on = data[Constants.ON]?.let {
            PerNodeInput(
                interfaze,
                Dict(it),
                { node: Node, value: Any -> NodeDisconnectFieldInputs.create(node, field, value) }
            )
        }
    }

    class NodeDisconnectFieldInputs(items: List<NodeDisconnectFieldInput>) : DisconnectFieldInput,
        InputListWrapper<NodeDisconnectFieldInput>(items) {

        companion object {
            fun create(node: Node, field: RelationField, value: Any?) = create(
                value,
                ::NodeDisconnectFieldInputs,
                { NodeDisconnectFieldInput(node, field, Dict(it)) }
            )
        }
    }

    class InterfaceDisconnectFieldInputs(items: List<InterfaceDisconnectFieldInput>) : DisconnectFieldInput,
        InputListWrapper<InterfaceDisconnectFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, field: RelationField, value: Any?) = create(
                value,
                ::InterfaceDisconnectFieldInputs,
                { InterfaceDisconnectFieldInput(interfaze, field, Dict(it)) }
            )
        }
    }

    class UnionDisconnectFieldInput(union: Union, field: RelationField, data: Dict) : DisconnectFieldInput,
        PerNodeInput<NodeDisconnectFieldInputs>(
            union,
            data,
            { node, value -> NodeDisconnectFieldInputs.create(node, field, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeDisconnectFieldInputs.create(it, field, value) },
            onInterface = { InterfaceDisconnectFieldInputs.create(it, field, value) },
            onUnion = { UnionDisconnectFieldInput(it, field, Dict(value)) }
        )
    }
}
