package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.nestedObject
import org.neo4j.graphql.wrapList

interface DisconnectFieldInput {

    class NodeDisconnectFieldInputs(data: List<NodeDisconnectFieldInput>) :
        DisconnectFieldInput,
        List<NodeDisconnectFieldInput> by data {
        companion object {

            fun create(
                field: RelationField,
                targetType: ImplementingType,
                data: Any?
            ): NodeDisconnectFieldInputs? {
                if (field.isUnion) {
                    TODO("Union")
                }
                return data
                    ?.wrapList()
                    ?.map { NodeDisconnectFieldInput.create(field, targetType, it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { NodeDisconnectFieldInputs(it) }
            }
        }
    }

    class UnionDisconnectFieldInput(data: Map<Node, NodeDisconnectFieldInputs>) :
        DisconnectFieldInput,
        PerNodeInput<NodeDisconnectFieldInputs>(data) {

        companion object {

            fun create(field: RelationField, union: Union, data: Any?) =
                create(::UnionDisconnectFieldInput, union, data, { node, value ->
                    NodeDisconnectFieldInputs.create(field, node, value)
                })

        }
    }

    data class NodeDisconnectFieldInput(
        val disconnect: List<DisconnectInput>? = null, // TODO can it be null?
        val where: ConnectionWhere? = null
    ) {

        companion object {

            fun create(field: RelationField, targetType: ImplementingType, data: Any): NodeDisconnectFieldInput {
                val disconnect = data.nestedObject(Constants.DISCONNECT_FIELD)
                    ?.wrapList()
                    ?.mapNotNull { DisconnectInput.create(targetType, it) }
                    ?.takeIf { it.isNotEmpty() }

                val where = data.nestedObject(Constants.WHERE)
                    ?.let { ConnectionWhere.create(field, it) }

                return NodeDisconnectFieldInput(disconnect, where)
            }

        }
    }

    companion object {
        fun create(field: RelationField, value: Any?) = field.extractOnTarget(
            onImplementingType = { NodeDisconnectFieldInputs.create(field, it, value) },
            onUnion = { UnionDisconnectFieldInput.create(field, it, value) }
        )
    }

}
