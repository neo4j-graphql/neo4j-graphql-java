package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.wrapList

interface UpdateFieldInputs {
    class NodeUpdateFieldInputs(data: List<UpdateFieldInput>) : UpdateFieldInputs, List<UpdateFieldInput> by data {
        companion object {
            fun create(field: RelationField, value: Any): UpdateFieldInputs = value
                .wrapList()
                .map { UpdateFieldInput.create(field, it) }
                .let { NodeUpdateFieldInputs(it) }
        }
    }

    class UnionUpdateFieldInputs(data: Map<Node, List<UpdateFieldInput>>) : UpdateFieldInputs,
        PerNodeInput<List<UpdateFieldInput>>(data) {

        companion object {
            fun create(field: RelationField, union: Union, data: Any?) =
                create(::UnionUpdateFieldInputs, union, data, { node, value ->
                    value?.wrapList()?.map { UpdateFieldInput.create(field, it, node) }
                })
        }
    }

    companion object {
        fun create(field: RelationField, value: Any): UpdateFieldInputs? = field.extractOnTarget(
            onImplementingType = { NodeUpdateFieldInputs.create(field, value) },
            onUnion = { UnionUpdateFieldInputs.create(field, it, value) }
        )
    }
}
