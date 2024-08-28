package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.fields.RelationBaseField

sealed class RelationshipBaseNames<T : RelationBaseField>(
    val relationship: T,
    annotations: FieldAnnotations
) : BaseNames<FieldAnnotations>(relationship.fieldName, annotations) {


    protected abstract val edgePrefix: String
    protected abstract val fieldInputPrefixForTypename: String
    val prefixForTypenameWithInheritance: String
        get() {
            val prefix = relationship.declarationOrSelf.getOwnerName()
            return prefix + relationship.fieldName.capitalize()
        }

    protected val prefixForTypename get() = "${relationship.getOwnerName()}${relationship.fieldName.capitalize()}"

    val connectionFieldTypename get() = "${prefixForTypenameWithInheritance}Connection"

    val connectionSortInputTypename get() = "${connectionFieldTypename}Sort"

    val connectionWhereInputTypename get() = "${connectionFieldTypename}Where"

    val relationshipFieldTypename get() = "${prefixForTypenameWithInheritance}Relationship"

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

    val connectOrCreateInputTypeName get() = "${prefixForTypename}ConnectOrCreateInput"

    fun getConnectOrCreateFieldInputTypeName(target: ImplementingType) =
        "$prefixForTypename${target.useNameIfFieldIsUnion()}ConnectOrCreateFieldInput"

    fun getConnectOrCreateOnCreateFieldInputTypeName(target: ImplementingType) =
        "${getConnectOrCreateFieldInputTypeName(target)}OnCreate"

    val connectionFieldName get() = "${prefixForTypenameWithInheritance}Connection"

    fun getConnectionWhereTypename(target: ImplementingType) =
        "$prefixForTypenameWithInheritance${target.useNameIfFieldIsUnion()}ConnectionWhere"

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

    val unionConnectionUnionWhereTypeName get() = "${prefixForTypenameWithInheritance.capitalize()}ConnectionWhere"

    val unionConnectInputTypeName get() = "${prefixForTypename.capitalize()}ConnectInput"

    val unionDeleteInputTypeName get() = "${prefixForTypename.capitalize()}DeleteInput"

    val unionDisconnectInputTypeName get() = "${prefixForTypename.capitalize()}DisconnectInput"

    val unionCreateInputTypeName get() = "${prefixForTypename.capitalize()}CreateInput"

    val unionCreateFieldInputTypeName get() = "${prefixForTypename.capitalize()}CreateFieldInput"

    val unionUpdateInputTypeName get() = "${prefixForTypename.capitalize()}UpdateInput"

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
