package org.neo4j.graphql.domain.inputs.connect_or_create

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.InputListWrapper
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.ScalarProperties

sealed interface ConnectOrCreateFieldInput {

    class NodeConnectOrCreateFieldInput(
        node: Node,
        field: RelationField,
        data: Dict
    ) {
        val where = data[Constants.WHERE]?.let { ConnectOrCreateWhere(node, Dict(it)) }
        val onCreate = data[Constants.ON_CREATE_FIELD]?.let { ConnectOrCreateFieldInputOnCreate(node, field, data) }
    }

    class NodeConnectOrCreateFieldInputs(items: List<NodeConnectOrCreateFieldInput>) : ConnectOrCreateFieldInput,
        InputListWrapper<NodeConnectOrCreateFieldInput>(items) {

        companion object {
            fun create(node: Node, field: RelationField, value: Any?) = create(
                value,
                ::NodeConnectOrCreateFieldInputs,
                { NodeConnectOrCreateFieldInput(node, field, Dict(it)) }
            )
        }
    }

    class UnionConnectOrCreateFieldInput(union: Union, field: RelationField, data: Dict) : ConnectOrCreateFieldInput,
        PerNodeInput<NodeConnectOrCreateFieldInputs>(
            union,
            data,
            { node, value -> NodeConnectOrCreateFieldInputs.create(node, field, Dict(value)) }
        )

    class ConnectOrCreateWhere(node: Node, data: Dict) {
        val node = data[Constants.NODE_FIELD]?.let {
            // UniqueWhere
            ScalarProperties.create(data, node)
        }
    }

    class ConnectOrCreateFieldInputOnCreate(node: Node, field: RelationField, data: Dict) {
        val node = data[Constants.NODE_FIELD]?.let {
            ScalarProperties.create(data, node)
        }

        val edge = field.properties?.let { props ->
            data[Constants.EDGE_FIELD]?.let {
                ScalarProperties.create(data, props)
            }
        }
    }

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeConnectOrCreateFieldInputs.create(it, field, value) },
            onInterface = { error("cannot connect to interface") },
            onUnion = { UnionConnectOrCreateFieldInput(it, field, Dict(value)) }
        )
    }
}
