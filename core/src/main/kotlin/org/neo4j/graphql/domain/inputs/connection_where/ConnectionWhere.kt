package org.neo4j.graphql.domain.inputs.connection_where

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.*
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
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) : ConnectionWhere, NestedWhere<ImplementingTypeConnectionWhere> {

        override val and: List<ImplementingTypeConnectionWhere>? =
            data[Constants.AND]?.wrapList()?.map { create(implementingType, relationshipProperties, it) }

        override val or: List<ImplementingTypeConnectionWhere>? =
            data[Constants.OR]?.wrapList()?.map { create(implementingType, relationshipProperties, it) }

        val predicates: List<ConnectionPredicate>? = ConnectionPredicate.getTargetOperationCombinations()
            .mapNotNull { (target, op) ->
                val key = target.targetName + op.suffix
                data[key]?.let { input ->
                    when (target) {
                        ConnectionPredicate.Target.NODE -> WhereInput.create(implementingType, input)
                        ConnectionPredicate.Target.EDGE -> WhereInput.create(relationshipProperties, input)
                    }
                }?.let { ConnectionPredicate(key, target, op, it) }
            }.takeIf { it.isNotEmpty() }

    }

    class NodeConnectionWhere(node: Node, relationshipProperties: RelationshipProperties?, data: Dict) :
        ImplementingTypeConnectionWhere(node, relationshipProperties, data)

    class InterfaceConnectionWhere(
        interfaze: Interface,
        relationshipProperties: RelationshipProperties?,
        data: Dict
    ) :
        ImplementingTypeConnectionWhere(interfaze, relationshipProperties, data) {

        val on = data[Constants.ON]?.let {
            PerNodeInput(interfaze, Dict(it), { node: Node, value: Any -> WhereInput.create(node, value) })
        }
    }

    class UnionConnectionWhere(union: Union, relationshipProperties: RelationshipProperties?, data: Dict) :
        ConnectionWhere,
        PerNodeInput<NodeConnectionWhere>(
            union,
            data,
            { node, value -> NodeConnectionWhere(node, relationshipProperties, Dict(value)) }
        )

    companion object {

        fun create(implementingType: ImplementingType, relationshipProperties: RelationshipProperties?, value: Any) =
            when (implementingType) {
                is Node -> NodeConnectionWhere(implementingType, relationshipProperties, Dict(value))
                is Interface -> InterfaceConnectionWhere(implementingType, relationshipProperties, Dict(value))
            }

        fun create(field: RelationField, value: Any) = field.extractOnTarget(
            onImplementingType = { create(it, field.properties, Dict(value)) },
            onUnion = { UnionConnectionWhere(it, field.properties, Dict(value)) }
        )
    }
}
