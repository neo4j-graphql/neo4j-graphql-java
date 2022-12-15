package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.nestedMap
import org.neo4j.graphql.nestedObject
import org.neo4j.graphql.wrapList

interface ConnectFieldInput {

    class NodeConnectFieldInputs(data: List<NodeConnectFieldInput>) :
        ConnectFieldInput,
        List<NodeConnectFieldInput> by data {
        companion object {

            fun create(
                field: RelationField,
                targetType: ImplementingType,
                data: Any?
            ): NodeConnectFieldInputs? {
                if (field.isUnion) {
                    TODO("Union")
                }
                return data
                    ?.wrapList()
                    ?.map { NodeConnectFieldInput.create(field, targetType, it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { NodeConnectFieldInputs(it) }
            }
        }
    }

    class UnionConnectFieldInput(data: Map<Node, NodeConnectFieldInputs>) :
        ConnectFieldInput, PerNodeInput<NodeConnectFieldInputs>(data) {

        companion object {
            fun create(field: RelationField, union: Union, data: Any?): UnionConnectFieldInput? = create(
                ::UnionConnectFieldInput,
                union,
                data,
                { node, value -> NodeConnectFieldInputs.create(field, node, value) }
            )

        }
    }

    data class NodeConnectFieldInput(
        val connect: List<ConnectInput>?,
        val edge: ScalarProperties?,
        val where: ConnectWhere?
    ) {

        companion object {
            fun create(relationField: RelationField, targetType: ImplementingType, data: Any): NodeConnectFieldInput {
                val where = data.nestedMap(Constants.WHERE)
                    ?.let { ConnectWhere.create(targetType, it) }
                val edge = data.nestedMap(Constants.EDGE_FIELD)
                    ?.let { ScalarProperties.create(it, relationField.properties) }
                val connect = data.nestedObject(Constants.CONNECT_FIELD)
                    ?.wrapList()
                    ?.mapNotNull { ConnectInput.create(targetType, it) }
                    ?.takeIf { it.isNotEmpty() }
                return NodeConnectFieldInput(connect, edge, where)
            }
        }
    }

    companion object {
        fun create(field: RelationField, value: Any?): ConnectFieldInput? = field.extractOnTarget(
            onImplementingType = { NodeConnectFieldInputs.create(field, it, value) },
            onUnion = { UnionConnectFieldInput.create(field, it, value) }
        )
    }
}


