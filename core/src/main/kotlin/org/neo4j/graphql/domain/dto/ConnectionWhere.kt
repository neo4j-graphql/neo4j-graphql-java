package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.predicates.ConnectionPredicate
import org.neo4j.graphql.nestedObject
import org.neo4j.graphql.wrapList

interface ConnectionWhere {

    class UnionConnectionWhere(data: Map<Node, ImplementingTypeConnectionWhere>) : ConnectionWhere,
        PerNodeInput<ImplementingTypeConnectionWhere>(data) {

        companion object {
            fun create(field: RelationField, union: Union, data: Any) = create(
                ::UnionConnectionWhere, union, data
            ) { node, value -> ImplementingTypeConnectionWhere.create(field, node, value) }
        }
    }

    class ImplementingTypeConnectionWhere(
        override val and: List<ImplementingTypeConnectionWhere>? = null,
        override val or: List<ImplementingTypeConnectionWhere>? = null,
        val predicates: List<ConnectionPredicate>? = null,
    ) : ConnectionWhere, NestedWhere<ImplementingTypeConnectionWhere> {


        companion object {
            fun create(field: RelationField, type: ImplementingType, data: Any?): ImplementingTypeConnectionWhere {
                val and = data.nestedObject(Constants.AND)?.wrapList()?.map { create(field, type, it) }
                val or = data.nestedObject(Constants.OR)?.wrapList()?.map { create(field, type, it) }
                val predicates = mutableListOf<ConnectionPredicate>()
                ConnectionPredicate.Target.values().forEach { target ->
                    ConnectionPredicate.ConnectionOperator.values().forEach { op ->
                        val key = target.targetName + op.suffix
                        data.nestedObject(key)
                            ?.let {
                                when (target) {
                                    ConnectionPredicate.Target.NODE -> WhereInput.create(field, it)
                                    ConnectionPredicate.Target.EDGE -> WhereInput.FieldContainerWhereInput.create(
                                        field.properties,
                                        it
                                    )
                                }
                            }
                            ?.let {
                                predicates.add(ConnectionPredicate(key, target, op, it))
                            }
                    }
                }
                return ImplementingTypeConnectionWhere(and, or, predicates.takeIf { it.isNotEmpty() })
            }
        }
    }

    companion object {
        fun create(field: RelationField, data: Any): ConnectionWhere? = field.extractOnTarget(
            onImplementingType = { ImplementingTypeConnectionWhere.create(field, it, data) },
            onUnion = { UnionConnectionWhere.create(field, it, data) },
        )
    }
}

