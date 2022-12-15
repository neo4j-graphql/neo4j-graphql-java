package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField

interface CreateInput {

    class NodeCreateInput(
        val relations: Map<RelationField, CreateRelationFieldInput>? = null,
        val properties: ScalarProperties? = null
    ) : CreateInput {
        companion object {

            fun create(node: Node, anyData: Any): NodeCreateInput {
                val map = anyData as Map<*, *>
                val properties = mutableMapOf<ScalarField, Any?>()
                val relations = mutableMapOf<RelationField, CreateRelationFieldInput>()
                map.forEach { (key, value) ->
                    val field = node.getField(key as String)
                        ?: throw IllegalArgumentException("unknwon field $key of node ${node.name}")
                    if (field is ScalarField) {
                        properties[field] = value
                    } else if (field is RelationField) {
                        relations[field] = CreateRelationFieldInput.create(field, value) ?: return@forEach
                    }
                }
                return NodeCreateInput(
                    relations.takeIf { it.isNotEmpty() },
                    properties.takeIf { it.isNotEmpty() }?.let { ScalarProperties(it) }
                )
            }
        }
    }

    class InterfaceCreateInput(data: Map<Node, NodeCreateInput>) : CreateInput, PerNodeInput<NodeCreateInput>(data) {
        companion object {

            fun create(type: Interface, data: Any): InterfaceCreateInput? = create(
                ::InterfaceCreateInput, type, data,
                { node, value -> NodeCreateInput.create(node, requireNotNull(value)) }
            )
        }

    }

    companion object {
        fun create(targetType: ImplementingType, data: Any): CreateInput? = when (targetType) {
            is Node -> NodeCreateInput.create(targetType, data)
            is Interface -> InterfaceCreateInput.create(targetType, data)
            else -> throw IllegalStateException("unsupported implementation type")
        }
    }

}
