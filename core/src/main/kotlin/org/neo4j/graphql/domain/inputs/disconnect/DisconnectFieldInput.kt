package org.neo4j.graphql.domain.inputs.disconnect

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.InputListWrapper
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere.InterfaceConnectionWhere
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere.NodeConnectionWhere
import org.neo4j.graphql.wrapList

sealed interface DisconnectFieldInput {

    sealed interface ImplementingTypeDisconnectFieldInput {
        val where: ConnectionWhere?
        val disconnect: List<DisconnectInput>?
    }

    class NodeDisconnectFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeDisconnectFieldInput {

        override val where = data[Constants.WHERE]?.let {
            NodeConnectionWhere(node, relationshipProperties, Dict(it))
        }

        override val disconnect = data[Constants.DISCONNECT_FIELD]
            ?.wrapList()?.map { DisconnectInput.NodeDisconnectInput(node, Dict(it)) }
    }

    class InterfaceDisconnectFieldInput(
        interfaze: Interface,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) :
        ImplementingTypeDisconnectFieldInput {

        override val where = data[Constants.WHERE]?.let {
            InterfaceConnectionWhere(interfaze, relationshipProperties, Dict(it))
        }

        override val disconnect = data[Constants.DISCONNECT_FIELD]
            ?.wrapList()?.map { DisconnectInput.InterfaceDisconnectInput(interfaze, Dict(it)) }

        val on = data[Constants.ON]?.let {
            PerNodeInput(
                interfaze,
                Dict(it),
                { node: Node, value: Any -> NodeDisconnectFieldInputs.create(node, relationshipProperties, value) }
            )
        }
    }

    class NodeDisconnectFieldInputs(items: List<NodeDisconnectFieldInput>) : DisconnectFieldInput,
        InputListWrapper<NodeDisconnectFieldInput>(items) {

        companion object {
            fun create(node: Node, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::NodeDisconnectFieldInputs,
                { NodeDisconnectFieldInput(node, relationshipProperties, Dict(it)) }
            )
        }
    }

    class InterfaceDisconnectFieldInputs(items: List<InterfaceDisconnectFieldInput>) : DisconnectFieldInput,
        InputListWrapper<InterfaceDisconnectFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::InterfaceDisconnectFieldInputs,
                { InterfaceDisconnectFieldInput(interfaze, relationshipProperties, Dict(it)) }
            )
        }
    }

    class UnionDisconnectFieldInput(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) :
        DisconnectFieldInput,
        PerNodeInput<NodeDisconnectFieldInputs>(
            union,
            data,
            { node, value -> NodeDisconnectFieldInputs.create(node, relationshipProperties, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeDisconnectFieldInputs.create(it, field.properties, value) },
            onInterface = { InterfaceDisconnectFieldInputs.create(it, field.properties, value) },
            onUnion = { UnionDisconnectFieldInput(it, field.properties, Dict(value)) }
        )
    }
}
