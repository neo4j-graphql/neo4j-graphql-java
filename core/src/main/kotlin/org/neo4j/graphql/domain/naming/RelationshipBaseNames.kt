package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationBaseField

sealed class RelationshipBaseNames<T : RelationBaseField>(
    val relationship: T,
) : BaseNames(relationship.fieldName) {


    protected abstract val edgePrefix: String
    protected abstract val fieldInputPrefixForTypename: String
    private val prefixForTypenameWithInheritance: String
        get() {
            val prefix = relationship.getOwnerName()
            return prefix + relationship.fieldName.capitalize()
        }

    protected val prefixForTypename get() = "${relationship.getOwnerName()}${relationship.fieldName.capitalize()}"

    val connectionFieldTypename get() = "${prefixForTypenameWithInheritance}Connection"

    val connectionSortInputTypename get() = "${connectionFieldTypename}Sort"

    val connectionWhereInputTypename get() = "${connectionFieldTypename}Where"

    val relationshipFieldTypename get() = "${prefixForTypenameWithInheritance}Relationship"

    val connectionFieldName get() = "${relationship.fieldName}Connection"

    fun getConnectionWhereTypename(target: ImplementingType) =
        "$prefixForTypenameWithInheritance${target.useNameIfFieldIsUnion()}ConnectionWhere"

    val unionConnectionUnionWhereTypeName get() = "${prefixForTypenameWithInheritance.capitalize()}ConnectionWhere"

    override val whereInputTypeName get() = "${edgePrefix}Where"

    val sortInputTypeName get() = "${edgePrefix}Sort"

    protected fun ImplementingType.useNameIfFieldIsUnion() = name.takeIf { relationship.target is Union } ?: ""
}
