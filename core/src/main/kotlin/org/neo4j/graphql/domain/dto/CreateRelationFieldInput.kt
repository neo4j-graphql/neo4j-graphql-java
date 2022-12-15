package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.nestedObject
import org.neo4j.graphql.wrapList

interface CreateRelationFieldInput {

    class NodeCreateRelationFieldInput(
        val connect: ConnectFieldInput.NodeConnectFieldInputs?,
        val create: List<CreateFieldInput>?,
        val connectOrCreate: List<ConnectOrCreateInput>?,
    ) : CreateRelationFieldInput {

        companion object {
            fun create(field: RelationField, type: ImplementingType, value: Any?): NodeCreateRelationFieldInput {
                val connect = value.nestedObject(Constants.CONNECT_FIELD)
                    ?.wrapList()
                    ?.let { ConnectFieldInput.NodeConnectFieldInputs.create(field, type, it) }

                val create = value.nestedObject(Constants.CREATE_FIELD)
                    ?.wrapList()
                    ?.map { CreateFieldInput.create(field, type, it) }
                    ?.takeIf { it.isNotEmpty() }

                val connectOrCreate = value.nestedObject(Constants.CONNECT_OR_CREATE_FIELD)
                    ?.wrapList()
                    ?.map { ConnectOrCreateInput.create(field, type, it) }
                    ?.takeIf { it.isNotEmpty() }

                return NodeCreateRelationFieldInput(connect, create, connectOrCreate)
            }
        }
    }

    class UnionCreateRelationFieldInput(data: Map<Node, NodeCreateRelationFieldInput>) :
        CreateRelationFieldInput, PerNodeInput<NodeCreateRelationFieldInput>(data) {

        companion object {

            fun create(field: RelationField, union: Union, data: Any?) = create(
                ::UnionCreateRelationFieldInput,
                union,
                data,
                { node, value -> NodeCreateRelationFieldInput.create(field, node, value) }
            )
        }

    }

    companion object {
        fun create(field: RelationField, value: Any?): CreateRelationFieldInput? = field.extractOnTarget(
            onImplementingType = { NodeCreateRelationFieldInput.create(field, it, value) },
            onUnion = { UnionCreateRelationFieldInput.create(field, it, value) }
        )
    }

}
