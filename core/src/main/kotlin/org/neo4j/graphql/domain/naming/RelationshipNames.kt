package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.fields.RelationField

class RelationshipNames(
    relationship: RelationField,
    annotations: FieldAnnotations
) : RelationshipBaseNames<RelationField>(relationship, annotations) {


    override val edgePrefix
        get() = relationship.properties?.typeName ?: "__ERROR__" // TODO find better way to handle this

    override val fieldInputPrefixForTypename
        get() = (relationship.getOwnerName().takeIf { relationship.target is Interface }
            ?: relationship.connectionPrefix) + relationship.fieldName.capitalize()

    val subscriptionWhereInputTypeName get() = "${prefixForTypename}RelationshipSubscriptionWhere"

    fun getToUnionUpdateInputTypeName(target: ImplementingType) =
        "$prefixForTypename${target.useNameIfFieldIsUnion()}UpdateInput"

    val subscriptionConnectedRelationshipTypeName get() = "${prefixForTypename}ConnectedRelationship"

    val edgeSubscriptionWhereInputTypeName get() = "${edgePrefix}SubscriptionWhere"

}
