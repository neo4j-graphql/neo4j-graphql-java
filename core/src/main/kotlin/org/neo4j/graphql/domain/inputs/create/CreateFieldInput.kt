package org.neo4j.graphql.domain.inputs.create

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.connect.ConnectFieldInput
import org.neo4j.graphql.domain.inputs.connect_or_create.ConnectOrCreateFieldInput
import org.neo4j.graphql.wrapList

sealed interface CreateFieldInput {

    sealed class ImplementingTypeFieldInput {
        abstract val create: List<RelationFieldInput.ImplementingTypeCreateFieldInput>?
        abstract val connect: List<ConnectFieldInput.ImplementingTypeConnectFieldInput>?
    }

    class NodeFieldInput(node: Node, field: RelationField, data: Dict) :
        CreateFieldInput,
        ImplementingTypeFieldInput() {

        override val create = data[Constants.CREATE_FIELD]
            ?.wrapList()
            ?.map { RelationFieldInput.NodeCreateCreateFieldInput.create(node, it) }
            ?.takeIf { it.isNotEmpty() }

        override val connect = data[Constants.CONNECT_FIELD]
            ?.let { ConnectFieldInput.NodeConnectFieldInputs.create(node, field, it) }

        val connectOrCreate = data[Constants.CONNECT_OR_CREATE_FIELD]
            ?.let { ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInputs.create(node, field, it) }

    }

    class InterfaceFieldInput(interfaze: Interface, field: RelationField, data: Dict) :
        CreateFieldInput,
        ImplementingTypeFieldInput() {
        override val create = data[Constants.CREATE_FIELD]
            ?.wrapList()
            ?.map { RelationFieldInput.InterfaceCreateFieldInput.create(interfaze, it) }
            ?.takeIf { it.isNotEmpty() }

        override val connect = data[Constants.CONNECT_FIELD]
            ?.let { ConnectFieldInput.InterfaceConnectFieldInputs.create(interfaze, field, it) }
    }


    class UnionFieldInput(union: Union, field: RelationField, data: Dict) : CreateFieldInput,
        PerNodeInput<NodeFieldInput>(union, data, { node, value -> NodeFieldInput(node, field, Dict(value)) })

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeFieldInput(it, field, Dict(value)) },
            onInterface = { InterfaceFieldInput(it, field, Dict(value)) },
            onUnion = { UnionFieldInput(it, field, Dict(value)) }
        )
    }
}
