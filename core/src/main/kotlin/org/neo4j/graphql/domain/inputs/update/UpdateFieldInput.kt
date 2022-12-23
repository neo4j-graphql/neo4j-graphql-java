package org.neo4j.graphql.domain.inputs.update

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.*
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.InputListWrapper
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.ScalarProperties
import org.neo4j.graphql.domain.inputs.connect.ConnectFieldInput
import org.neo4j.graphql.domain.inputs.connect_or_create.ConnectOrCreateFieldInput
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere.InterfaceConnectionWhere
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere.NodeConnectionWhere
import org.neo4j.graphql.domain.inputs.create.RelationFieldInput
import org.neo4j.graphql.domain.inputs.delete.DeleteFieldInput
import org.neo4j.graphql.domain.inputs.disconnect.DisconnectFieldInput

sealed interface UpdateFieldInput {

    sealed class ImplementingTypeUpdateFieldInput {
        abstract val where: ConnectionWhere.ImplementingTypeConnectionWhere<*>?
        abstract val update: ImplementingTypeUpdateConnectionInput?
        abstract val connect: List<ConnectFieldInput.ImplementingTypeConnectFieldInput>?
        abstract val disconnect: List<DisconnectFieldInput.ImplementingTypeDisconnectFieldInput>?
        abstract val create: RelationFieldInput?
        abstract val delete: DeleteFieldInput?
    }

    class NodeUpdateFieldInput(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeUpdateFieldInput() {
        override val where = data[Constants.WHERE]?.let {
            NodeConnectionWhere(node, relationshipProperties, Dict(it))
        }

        override val update = data[Constants.UPDATE_FIELD]?.let {
            NodeUpdateConnectionInput(node, Dict(it))
        }

        override val connect = data[Constants.CONNECT_FIELD]?.let {
            ConnectFieldInput.NodeConnectFieldInputs.create(node, relationshipProperties, it)
        }

        override val disconnect = data[Constants.DISCONNECT_FIELD]?.let {
            DisconnectFieldInput.NodeDisconnectFieldInputs.create(node, relationshipProperties, it)
        }

        override val create =
            data[Constants.CREATE_FIELD]?.let { RelationFieldInput.NodeCreateCreateFieldInputs.create(node, it) }

        override val delete = data[Constants.DELETE_FIELD]?.let {
            DeleteFieldInput.NodeDeleteFieldInputs.create(node, relationshipProperties, it)
        }

        val connectOrCreate = data[Constants.CONNECT_OR_CREATE_FIELD]?.let {
            ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInputs.create(node, relationshipProperties, it)
        }
    }

    class InterfaceUpdateFieldInput(interfaze: Interface, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeUpdateFieldInput() {

        override val update = data[Constants.UPDATE_FIELD]?.let {
            InterfaceUpdateConnectionInput(interfaze, data)
        }

        override val connect = data[Constants.CONNECT_FIELD]?.let {
            ConnectFieldInput.InterfaceConnectFieldInputs.create(interfaze, relationshipProperties, it)
        }

        override val disconnect = data[Constants.DISCONNECT_FIELD]?.let {
            DisconnectFieldInput.InterfaceDisconnectFieldInputs.create(interfaze, relationshipProperties, it)
        }

        override val create =
            data[Constants.CREATE_FIELD]?.let { RelationFieldInput.InterfaceCreateFieldInputs.create(interfaze, it) }

        override val delete = data[Constants.DELETE_FIELD]?.let {
            DeleteFieldInput.InterfaceDeleteFieldInputs.create(interfaze, relationshipProperties, it)
        }

        override val where = data[Constants.WHERE]?.let {
            InterfaceConnectionWhere(interfaze, relationshipProperties, Dict(it))
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
            fun create(node: Node, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::NodeUpdateFieldInputs,
                { NodeUpdateFieldInput(node, relationshipProperties, Dict(it)) }
            )
        }
    }

    class InterfaceUpdateFieldInputs(items: List<InterfaceUpdateFieldInput>) : UpdateFieldInput,
        InputListWrapper<InterfaceUpdateFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, relationshipProperties: RelationshipProperties?, value: Any?) = create(
                value,
                ::InterfaceUpdateFieldInputs,
                { InterfaceUpdateFieldInput(interfaze, relationshipProperties, Dict(it)) }
            )
        }
    }

    class UnionUpdateFieldInput(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) :
        UpdateFieldInput,
        PerNodeInput<NodeUpdateFieldInputs>(
            union,
            data,
            { node, value -> NodeUpdateFieldInputs.create(node, relationshipProperties, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeUpdateFieldInputs.create(it, field.properties, value) },
            onInterface = { InterfaceUpdateFieldInputs.create(it, field.properties, value) },
            onUnion = { UnionUpdateFieldInput(it, field.properties, Dict(value)) }
        )
    }
}
