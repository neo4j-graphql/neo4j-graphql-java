package org.neo4j.graphql.domain.inputs.delete

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.InputListWrapper
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere

sealed interface DeleteFieldInput {
    sealed interface ImplementingTypeDeleteFieldInput {
        val where: ConnectionWhere.ImplementingTypeConnectionWhere?
        val delete: DeleteInput?
    }

    class NodeDeleteFieldInput(node: Node, field: RelationField, data: Dict) :
        ImplementingTypeDeleteFieldInput {

        override val where =
            data[Constants.WHERE]?.let { ConnectionWhere.NodeConnectionWhere.create(node, field, Dict(it)) }

        override val delete = data[Constants.DELETE_FIELD]?.let { DeleteInput.NodeDeleteInput(node, Dict(it)) }
    }

    class InterfaceDeleteFieldInput(interfaze: Interface, field: RelationField, data: Dict) :
        ImplementingTypeDeleteFieldInput {

        override val where =
            data[Constants.WHERE]?.let { ConnectionWhere.InterfaceConnectionWhere.create(interfaze, field, Dict(it)) }

        override val delete =
            data[Constants.DELETE_FIELD]?.let { DeleteInput.InterfaceDeleteInput(interfaze, Dict(it)) }

        val on = data[Constants.ON]?.let {
            PerNodeInput(
                interfaze,
                Dict(it),
                { node: Node, value: Any -> NodeDeleteFieldInputs.create(node, field, value) }
            )
        }
    }

    class NodeDeleteFieldInputs(items: List<NodeDeleteFieldInput>) : DeleteFieldInput,
        InputListWrapper<NodeDeleteFieldInput>(items) {

        companion object {
            fun create(node: Node, field: RelationField, value: Any?) = create(
                value,
                ::NodeDeleteFieldInputs,
                { NodeDeleteFieldInput(node, field, Dict(it)) }
            )
        }
    }

    class InterfaceDeleteFieldInputs(items: List<InterfaceDeleteFieldInput>) : DeleteFieldInput,
        InputListWrapper<InterfaceDeleteFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, field: RelationField, value: Any?) = create(
                value,
                ::InterfaceDeleteFieldInputs,
                { InterfaceDeleteFieldInput(interfaze, field, Dict(it)) }
            )
        }
    }

    class UnionDeleteFieldInput(union: Union, field: RelationField, data: Dict) : DeleteFieldInput,
        PerNodeInput<NodeDeleteFieldInputs>(
            union,
            data,
            { node, value -> NodeDeleteFieldInputs.create(node, field, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onNode = { NodeDeleteFieldInputs.create(it, field, value) },
            onInterface = { InterfaceDeleteFieldInputs.create(it, field, value) },
            onUnion = { UnionDeleteFieldInput(it, field, Dict(value)) }
        )
    }
}
