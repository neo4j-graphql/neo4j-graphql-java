package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.domain.fields.RelationField

class RelationshipOperations(
    val relationship: RelationField,
    annotations: Annotations
) : BaseNames(relationship.fieldName, annotations) {

    // TODO use ownername?
    val prefixForTypename get() = relationship.getOwnerName()
    private val prefix get() = "${prefixForTypename}${name.capitalize()}"

    val connectionFieldTypename get() = "${prefix}Connection"
    val connectionSortInputTypename get() = "${connectionFieldTypename}Sort"
    val connectionWhereInputTypename get() = "${connectionFieldTypename}Where"
    val relationshipFieldTypename get() = "${prefix}Relationship"
    val subscriptionConnectedRelationshipTypeName get() = "${prefix}ConnectedRelationship"
    val subscriptionWhereInputTypeName get() = "${prefix}RelationshipSubscriptionWhere"
    val edgeSubscriptionWhereInputTypeName get() = "${relationship.properties?.interfaceName}SubscriptionWhere"
    fun getToUnionSubscriptionWhereInputTypeName(node: Node) = "${prefix}${node.name}SubscriptionWhere"
    fun getUpdateFieldInputTypeName(implementingType: ImplementingType) =
        "${prefix}${implementingType.name}UpdateFieldInput"

    val aggregateInputTypeName get() = "${prefix}AggregateInput"

    val aggregateTypeNames get() = relationship.implementingType?.let { AggregateTypeNames(it) }

    inner class AggregateTypeNames(implementingType: ImplementingType) {
        private val prefix = relationship.getOwnerName() + implementingType.name + name.capitalize()
        val field get() = "${prefix}AggregationSelection"
        val node get() = "${prefix}NodeAggregateSelection"
        val edge get() = "${prefix}EdgeAggregateSelection"
    }
}
