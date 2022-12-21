package org.neo4j.graphql.domain.inputs.create

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

sealed interface RelationFieldInput {
    sealed class ImplementingTypeCreateFieldInput(implementingType: ImplementingType, data: Dict) {
        val edge = data[Constants.EDGE_FIELD]?.let { ScalarProperties.create(data, implementingType) }
    }

    class NodeCreateCreateFieldInput(node: Node, value: Dict) : ImplementingTypeCreateFieldInput(node, value) {
        val node = CreateInput.create(node, value)

        companion object {
            fun create(node: Node, value: Any?): NodeCreateCreateFieldInput {
                return NodeCreateCreateFieldInput(node, Dict(value))
            }
        }
    }

    class InterfaceCreateFieldInput(interfaze: Interface, data: Dict) :
        ImplementingTypeCreateFieldInput(interfaze, data) {

        val node = PerNodeInput(interfaze, Dict(data), { node: Node, value: Any -> CreateInput.create(node, value) })

        companion object {
            fun create(interfaze: Interface, value: Any): InterfaceCreateFieldInput {
                return InterfaceCreateFieldInput(interfaze, Dict(value))
            }
        }
    }

    class NodeCreateCreateFieldInputs(items: List<NodeCreateCreateFieldInput>) : RelationFieldInput,
        InputListWrapper<NodeCreateCreateFieldInput>(items) {

        companion object {
            fun create(node: Node, value: Any?) = create(
                value,
                ::NodeCreateCreateFieldInputs,
                { NodeCreateCreateFieldInput(node, Dict(it)) }
            )
        }
    }

    class InterfaceCreateFieldInputs(items: List<InterfaceCreateFieldInput>) : RelationFieldInput,
        InputListWrapper<InterfaceCreateFieldInput>(items) {
        companion object {
            fun create(interfaze: Interface, value: Any?) = create(
                value,
                ::InterfaceCreateFieldInputs,
                { InterfaceCreateFieldInput(interfaze, Dict(it)) }
            )
        }
    }

    class UnionFieldInput(union: Union, data: Dict) : RelationFieldInput,
        PerNodeInput<NodeCreateCreateFieldInputs>(
            union,
            data,
            { node, value -> NodeCreateCreateFieldInputs.create(node, value) }
        )

    companion object {
        fun create(field: RelationField, value: Any) =
            field.extractOnTarget(
                onNode = { NodeCreateCreateFieldInputs.create(it, value) },
                onInterface = { InterfaceCreateFieldInputs.create(it, value) },
                onUnion = { UnionFieldInput(it, Dict(value)) }
            )

    }

}


