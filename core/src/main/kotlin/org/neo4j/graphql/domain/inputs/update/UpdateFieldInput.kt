package org.neo4j.graphql.domain.inputs.update

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.InputListWrapper
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.ScalarProperties
import org.neo4j.graphql.domain.inputs.connect.ConnectFieldInput
import org.neo4j.graphql.domain.inputs.connect_or_create.ConnectOrCreateFieldInput
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere
import org.neo4j.graphql.domain.inputs.create.RelationFieldInput
import org.neo4j.graphql.domain.inputs.delete.DeleteFieldInput
import org.neo4j.graphql.domain.inputs.disconnect.DisconnectFieldInput

sealed interface UpdateFieldInput {

    sealed class ImplementingTypeUpdateFieldInput {
        abstract val where: ConnectionWhere.ImplementingTypeConnectionWhere?
        abstract val update: ImplementingTypeUpdateConnectionInput?
        abstract val connect: List<ConnectFieldInput.ImplementingTypeConnectFieldInput>?
        abstract val disconnect: List<DisconnectFieldInput.ImplementingTypeDisconnectFieldInput>?
        abstract val create: RelationFieldInput?
        abstract val delete: DeleteFieldInput?
    }

    class NodeUpdateFieldInput(node: Node, field: RelationField, data: Dict) : ImplementingTypeUpdateFieldInput() {
        override val where = data[Constants.WHERE]?.let {
            ConnectionWhere.NodeConnectionWhere.create(node, field, Dict(it))
        }

        override val update = data[Constants.UPDATE_FIELD]?.let {
            NodeUpdateConnectionInput(node, Dict(it))
        }

        override val connect = data[Constants.CONNECT_FIELD]?.let {
            ConnectFieldInput.NodeConnectFieldInputs.create(node, field, it)
        }

        override val disconnect = data[Constants.DISCONNECT_FIELD]?.let {
            DisconnectFieldInput.NodeDisconnectFieldInputs.create(node, field, it)
        }

        override val create =
            data[Constants.CREATE_FIELD]?.let { RelationFieldInput.NodeCreateCreateFieldInputs.create(node, it) }

        override val delete = data[Constants.DELETE_FIELD]?.let {
            DeleteFieldInput.NodeDeleteFieldInputs.create(node, field, it)
        }

        val connectOrCreate = data[Constants.CONNECT_OR_CREATE_FIELD]?.let {
            ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInputs.create(node, field, it)
        }
    }

    class InterfaceUpdateFieldInput(interfaze: Interface, field: RelationField, data: Dict) :
        ImplementingTypeUpdateFieldInput() {

        override val update = data[Constants.UPDATE_FIELD]?.let {
            InterfaceUpdateConnectionInput(interfaze, data)
        }

        override val connect = data[Constants.CONNECT_FIELD]?.let {
            ConnectFieldInput.InterfaceConnectFieldInputs.create(interfaze, field, it)
        }

        override val disconnect = data[Constants.DISCONNECT_FIELD]?.let {
            DisconnectFieldInput.InterfaceDisconnectFieldInputs.create(interfaze, field, it)
        }

        override val create =
            data[Constants.CREATE_FIELD]?.let { RelationFieldInput.InterfaceCreateFieldInputs.create(interfaze, it) }

        override val delete = data[Constants.DELETE_FIELD]?.let {
            DeleteFieldInput.InterfaceDeleteFieldInputs.create(interfaze, field, it)
        }

        override val where = data[Constants.WHERE]?.let {
            ConnectionWhere.InterfaceConnectionWhere.create(interfaze, field, Dict(it))
        }

    }

    sealed class ImplementingTypeUpdateConnectionInput(implementingType: ImplementingType, data: Dict) {
        val edge = data[Constants.EDGE_FIELD]?.let { ScalarProperties.create(data, implementingType) }
        abstract val node: UpdateInput?
    }

    class InterfaceUpdateConnectionInput(interfaze: Interface, data: Dict) :
        ImplementingTypeUpdateConnectionInput(interfaze, data) {
        override val node = data[Constants.NODE_FIELD]?.let { UpdateInput.InterfaceUpdateInput(interfaze, data) }
    }

    class NodeUpdateConnectionInput(node: Node, data: Dict) :
        ImplementingTypeUpdateConnectionInput(node, data) {
        override val node = data[Constants.NODE_FIELD]?.let { UpdateInput.NodeUpdateInput(node, data) }
    }

    class NodeUpdateFieldInputs(items: List<NodeUpdateFieldInput>) : UpdateFieldInput,
        InputListWrapper<NodeUpdateFieldInput>(items) {

        companion object {
            fun create(node: Node, field: RelationField, value: Any?) = create(
                value,
                ::NodeUpdateFieldInputs,
                { NodeUpdateFieldInput(node, field, Dict(it)) }
            )
        }
    }

    class InterfaceUpdateFieldInputs(items: List<InterfaceUpdateFieldInput>) : UpdateFieldInput,
        InputListWrapper<InterfaceUpdateFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, field: RelationField, value: Any?) = create(
                value,
                ::InterfaceUpdateFieldInputs,
                { InterfaceUpdateFieldInput(interfaze, field, Dict(it)) }
            )
        }
    }

    class UnionUpdateFieldInput(union: Union, field: RelationField, data: Dict) : UpdateFieldInput,
        PerNodeInput<NodeUpdateFieldInputs>(
            union,
            data,
            { node, value -> NodeUpdateFieldInputs.create(node, field, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeUpdateFieldInputs.create(it, field, value) },
            onInterface = { InterfaceUpdateFieldInputs.create(it, field, value) },
            onUnion = { UnionUpdateFieldInput(it, field, Dict(value)) }
        )
    }
}
