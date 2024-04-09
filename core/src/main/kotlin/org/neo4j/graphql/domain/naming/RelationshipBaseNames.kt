package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.domain.fields.RelationField

sealed class RelationshipBaseNames<T : RelationBaseField>(
    val relationship: T,
    annotations: FieldAnnotations
) : BaseNames<FieldAnnotations>(relationship.fieldName, annotations) {


    protected abstract val edgePrefix: String
    protected abstract val fieldInputPrefixForTypename: String
    val prefixForTypenameWithInheritance: String
        get() {
            var prefix = relationship.getOwnerName()
            if (relationship is RelationField && relationship.inheritedFrom != null) {
                prefix += relationship.inheritedFrom
            }
            return prefix + relationship.fieldName.capitalize()
        }

    private val prefixForConnectionTypename get() = "${relationship.connectionPrefix}${relationship.fieldName.capitalize()}"

    protected val prefixForTypename get() = "${relationship.getOwnerName()}${relationship.fieldName.capitalize()}"

    val connectionFieldTypename get() = "${prefixForConnectionTypename}Connection"

    val connectionSortInputTypename get() = "${connectionFieldTypename}Sort"

    val connectionWhereInputTypename get() = "${connectionFieldTypename}Where"

    val relationshipFieldTypename get() = "${prefixForTypenameWithInheritance}Relationship"
    val relationshipFieldTypename2 get() = "${prefixForConnectionTypename}Relationship"

    fun getFieldInputTypeName(target: ImplementingType) =
        "$prefixForTypename${target.useNameIfFieldIsUnion()}FieldInput"

    fun getUpdateFieldInputTypeName(target: ImplementingType) =
        "$prefixForTypename${target.useNameIfFieldIsUnion()}UpdateFieldInput"

    fun getCreateFieldInputTypeName(target: ImplementingType) =
        "$prefixForTypename${target.useNameIfFieldIsUnion()}CreateFieldInput"

    fun getDeleteFieldInputTypeName(target: ImplementingType) =
        "$fieldInputPrefixForTypename${target.useNameIfFieldIsUnion()}DeleteFieldInput"

    fun getConnectFieldInputTypeName(target: ImplementingType) =
        "$prefixForTypename${target.useNameIfFieldIsUnion()}ConnectFieldInput"

    fun getDisconnectFieldInputTypeName(target: ImplementingType) =
        "$fieldInputPrefixForTypename${target.useNameIfFieldIsUnion()}DisconnectFieldInput"

    val connectOrCreateInputTypeName get() = "${prefixForTypenameWithInheritance}ConnectOrCreateInput"

    fun getConnectOrCreateFieldInputTypeName(target: ImplementingType) =
        "$prefixForConnectionTypename${target.useNameIfFieldIsUnion()}ConnectOrCreateFieldInput"

    fun getConnectOrCreateOnCreateFieldInputTypeName(target: ImplementingType) =
        "${getConnectOrCreateFieldInputTypeName(target)}OnCreate"

    val connectionFieldName get() = "${prefixForTypenameWithInheritance}Connection"

    fun getConnectionWhereTypename(target: ImplementingType) =
        "$prefixForConnectionTypename${target.useNameIfFieldIsUnion()}ConnectionWhere"

    fun getUpdateConnectionInputTypename(target: ImplementingType) =
        "$prefixForTypename${target.useNameIfFieldIsUnion()}UpdateConnectionInput"

    val aggregateInputTypeName get() = "${prefixForTypename}AggregateInput"
    val nodeAggregationWhereInputTypeName get() = "${prefixForTypename}NodeAggregationWhereInput"
    val edgeAggregationWhereInputTypeName get() = "${edgePrefix}AggregationWhereInput"

    // TODO better name for this?
    val aggregateTypeName get() = "${relationship.fieldName}Aggregate"

    val aggregateTypeNames get() = relationship.implementingType?.let { AggregateTypeNames(it) }

    inner class AggregateTypeNames(implementingType: ImplementingType) {
        private val prefix = relationship.getOwnerName() + implementingType.name + name.capitalize()
        val field get() = "${prefix}AggregationSelection"
        val node get() = "${prefix}NodeAggregateSelection"
        val edge get() = "${prefix}EdgeAggregateSelection"
    }


    fun getToUnionSubscriptionWhereInputTypeName(target: ImplementingType) =
        "$prefixForTypename${target.useNameIfFieldIsUnion()}SubscriptionWhere"

    val unionConnectionUnionWhereTypeName get() = "${prefixForConnectionTypename.capitalize()}ConnectionWhere"

    val unionConnectInputTypeName get() = "${prefixForConnectionTypename.capitalize()}ConnectInput"

    val unionDeleteInputTypeName get() = "${prefixForConnectionTypename.capitalize()}DeleteInput"

    val unionDisconnectInputTypeName get() = "${prefixForConnectionTypename.capitalize()}DisconnectInput"

    val unionCreateInputTypeName get() = "${prefixForConnectionTypename.capitalize()}CreateInput"

    val unionCreateFieldInputTypeName get() = "${prefixForConnectionTypename.capitalize()}CreateFieldInput"

    val unionUpdateInputTypeName get() = "${prefixForConnectionTypename.capitalize()}UpdateInput"

    val createInputTypeName get() = "${edgePrefix}CreateInput"

    val edgeUpdateInputTypeName get() = "${edgePrefix}UpdateInput"

    override val whereInputTypeName get() = "${edgePrefix}Where"

    val sortInputTypeName get() = "${edgePrefix}Sort"

    fun getConnectOrCreateInputFields(target: ImplementingType): Map<String, String> {
        return mapOf(
            "where" to "${target.namings.connectOrCreateWhereInputTypeName}!",
            "onCreate" to "${getConnectOrCreateOnCreateFieldInputTypeName(target)}!"
        )
    }

    protected fun ImplementingType.useNameIfFieldIsUnion() = name.takeIf { relationship.target is Union } ?: ""
}
