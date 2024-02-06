package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.fields.RelationField

class RelationshipNames(
    val relationship: RelationField,
    annotations: FieldAnnotations
) : BaseNames<FieldAnnotations>(relationship.fieldName, annotations) {


    private val fieldInputPrefixForTypename
        get() = (relationship.getOwnerName().takeIf { relationship.target is Interface }
            ?: relationship.connectionPrefix) + relationship.fieldName.capitalize()

    // TODO https://github.com/neo4j/graphql/issues/2684
    private val fieldInputPrefixForTypenameAlternative
        get() = (relationship.interfaceField?.getOwnerName()
            ?: relationship.getOwnerName()) + relationship.fieldName.capitalize()

    private val connectionPrefix
        get() = "${
            relationship.getOwnerName().capitalize()
        }${relationship.fieldName.capitalize()}"
    private val prefixForConnectionTypename get() = "${relationship.connectionPrefix}${relationship.fieldName.capitalize()}"
    private val prefixForTypename get() = "${relationship.getOwnerName()}${relationship.fieldName.capitalize()}"

    private val connectionFieldTypename get() = "${prefixForConnectionTypename}Connection"

    val connectionSortInputTypename get() = "${prefixForConnectionTypename}ConnectionSort"

    val connectionWhereInputTypename get() = "${prefixForConnectionTypename}ConnectionWhere"

    val relationshipFieldTypename get() = "${prefixForConnectionTypename}Relationship"

    fun getFieldInputTypeName(target: ImplementingType) =
        "$prefixForConnectionTypename${target.nameIfUnion()}FieldInput"

    fun getUpdateFieldInputTypeName(target: ImplementingType) =
        "$fieldInputPrefixForTypename${target.nameIfUnion()}UpdateFieldInput"

    fun getCreateFieldInputTypeName(target: ImplementingType) =
        "$fieldInputPrefixForTypename${target.nameIfUnion()}CreateFieldInput"

    fun getCreateFieldInputTypeNameAlternative(target: ImplementingType) =
        "${fieldInputPrefixForTypenameAlternative}${target.nameIfUnion()}CreateFieldInput"

    fun getDeleteFieldInputTypeName(target: ImplementingType) =
        "$fieldInputPrefixForTypename${target.nameIfUnion()}DeleteFieldInput"

    fun getConnectFieldInputTypeName(target: ImplementingType) =
        "$fieldInputPrefixForTypename${target.nameIfUnion()}ConnectFieldInput"

    fun getConnectFieldInputTypeNameAlternative(target: ImplementingType) =
        "${fieldInputPrefixForTypenameAlternative}${target.nameIfUnion()}ConnectFieldInput"

    fun getDisconnectFieldInputTypeName(target: ImplementingType) =
        "$fieldInputPrefixForTypename${target.nameIfUnion()}DisconnectFieldInput"

    val connectOrCreateInputTypeName get() = "${prefixForConnectionTypename}ConnectOrCreateInput"

    fun getConnectOrCreateFieldInputTypeName(target: ImplementingType) =
        "$prefixForConnectionTypename${target.nameIfUnion()}ConnectOrCreateFieldInput"

    fun getConnectOrCreateOnCreateFieldInputTypeName(target: ImplementingType) =
        "${getConnectOrCreateFieldInputTypeName(target)}OnCreate"

    val connectionFieldName get() = "${relationship.fieldName}Connection"

    fun getConnectionWhereTypename(target: ImplementingType) =
        "$prefixForConnectionTypename${target.nameIfUnion()}ConnectionWhere"

    fun getUpdateConnectionInputTypename(target: ImplementingType) =
        "$fieldInputPrefixForTypename${target.nameIfUnion()}UpdateConnectionInput"

    val aggregateInputTypeName get() = "${prefixForTypename}AggregateInput"
    val nodeAggregationWhereInputTypeName get() = "${prefixForTypename}NodeAggregationWhereInput"
    val edgeAggregationWhereInputTypeName get() = "${prefixForTypename}EdgeAggregationWhereInput"

    // TODO better name for this?
    val aggregateTypeName get() = "${relationship.fieldName}Aggregate"

    val aggregateTypeNames get() = relationship.implementingType?.let { AggregateTypeNames(it) }

    inner class AggregateTypeNames(implementingType: ImplementingType) {
        private val prefix = relationship.getOwnerName() + implementingType.name + name.capitalize()
        val field get() = "${prefix}AggregationSelection"
        val node get() = "${prefix}NodeAggregateSelection"
        val edge get() = "${prefix}EdgeAggregateSelection"
    }

    val subscriptionWhereInputTypeName get() = "${prefixForTypename}RelationshipSubscriptionWhere"

    fun getToUnionSubscriptionWhereInputTypeName(target: ImplementingType) =
        "$prefixForTypename${target.nameIfUnion()}SubscriptionWhere"

    val unionConnectionUnionWhereTypeName get() = "${connectionPrefix}ConnectionWhere"

    val unionConnectInputTypeName get() = "${connectionPrefix}ConnectInput"

    val unionDeleteInputTypeName get() = "${connectionPrefix}DeleteInput"

    val unionDisconnectInputTypeName get() = "${connectionPrefix}DisconnectInput"

    val unionCreateInputTypeName get() = "${connectionPrefix}CreateInput"

    val unionCreateFieldInputTypeName get() = "${connectionPrefix}CreateFieldInput"

    val unionUpdateInputTypeName get() = "${connectionPrefix}UpdateInput"

    fun getToUnionUpdateInputTypeName(target: ImplementingType) = "$prefixForTypename${target.nameIfUnion()}UpdateInput"

    val subscriptionConnectedRelationshipTypeName get() = "${prefixForTypename}ConnectedRelationship"

    val createInputTypeName get() = "${relationship.properties?.interfaceName}CreateInput"

    val edgeUpdateInputTypeName get() = "${relationship.properties?.interfaceName}UpdateInput"

    override val whereInputTypeName get() = "${relationship.properties?.interfaceName}Where"

    val edgeSubscriptionWhereInputTypeName get() = "${relationship.properties?.interfaceName}SubscriptionWhere"

    val sortInputTypeName get() = "${relationship.properties?.interfaceName}Sort"

    fun getConnectOrCreateInputFields(target: ImplementingType): Map<String, String> {
        return mapOf(
            "where" to "${target.namings.connectOrCreateWhereInputTypeName}!",
            "onCreate" to "${getConnectOrCreateOnCreateFieldInputTypeName(target)}!"
        )
    }

    private fun ImplementingType.nameIfUnion() = name.takeIf { relationship.target is Union } ?: ""
}
