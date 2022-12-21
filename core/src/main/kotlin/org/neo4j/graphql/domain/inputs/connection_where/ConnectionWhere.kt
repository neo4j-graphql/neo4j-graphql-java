package org.neo4j.graphql.domain.inputs.connection_where

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.NestedWhere
import org.neo4j.graphql.domain.inputs.PerNodeInput
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.predicates.ConnectionPredicate
import org.neo4j.graphql.wrapList

sealed interface ConnectionWhere {

    sealed class ImplementingTypeConnectionWhere(
        implementingType: ImplementingType,
        field: RelationField,
        data: Dict
    ) : ConnectionWhere, NestedWhere<ImplementingTypeConnectionWhere> {

        override val and: List<ImplementingTypeConnectionWhere>? =
            data[Constants.AND]?.wrapList()?.map { create(implementingType, field, it) }

        override val or: List<ImplementingTypeConnectionWhere>? =
            data[Constants.OR]?.wrapList()?.map { create(implementingType, field, it) }

        val predicates: List<ConnectionPredicate>? = ConnectionPredicate.Target.values().flatMap { target ->
            ConnectionPredicate.ConnectionOperator.values().map {
                target to it
            }
        }.mapNotNull { (target, op) ->
            val key = target.targetName + op.suffix
            data[key]?.let { input ->
                when (target) {
                    ConnectionPredicate.Target.NODE -> WhereInput.create(field, input)
                    ConnectionPredicate.Target.EDGE -> WhereInput.create(field.properties, input)
                }
            }?.let { ConnectionPredicate(key, target, op, it) }
        }.takeIf { it.isNotEmpty() }

    }

    class NodeConnectionWhere(node: Node, field: RelationField, data: Dict) :
        ImplementingTypeConnectionWhere(node, field, data) {
        companion object {
            fun create(node: Node, field: RelationField, dict: Dict): NodeConnectionWhere {
                return NodeConnectionWhere(node, field, dict)
            }
        }
    }

    class InterfaceConnectionWhere(interfaze: Interface, field: RelationField, data: Dict) :
        ImplementingTypeConnectionWhere(interfaze, field, data) {
        val on = data[Constants.ON]?.let {
            PerNodeInput(interfaze, Dict(it), { node: Node, value: Any -> WhereInput.create(node, value) })
        }

        companion object {
            fun create(interfaze: Interface, field: RelationField, dict: Dict): InterfaceConnectionWhere {
                return InterfaceConnectionWhere(interfaze, field, dict)
            }
        }
    }

    class UnionConnectionWhere(union: Union, field: RelationField, data: Dict) : ConnectionWhere,
        PerNodeInput<NodeConnectionWhere>(union, data, { node, value -> NodeConnectionWhere(node, field, Dict(value)) })

    companion object {

        fun create(implementingType: ImplementingType, field: RelationField, value: Any) = when (implementingType) {
            is Node -> NodeConnectionWhere(implementingType, field, Dict(value))
            is Interface -> InterfaceConnectionWhere(implementingType, field, Dict(value))
        }

        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onImplementingType = { create(it, field, Dict(value)) },
            onUnion = { UnionConnectionWhere(it, field, Dict(value)) }
        )
    }
}
