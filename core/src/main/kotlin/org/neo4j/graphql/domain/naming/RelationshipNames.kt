package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.fields.RelationField

class RelationshipNames(
    relationship: RelationField,
) : RelationshipBaseNames<RelationField>(relationship) {


    override val edgePrefix
        get() = relationship.properties?.typeName ?: "__ERROR__" // TODO find better way to handle this

    override val fieldInputPrefixForTypename: String
        get() {
            val prefix = (relationship.getOwnerName().takeIf { relationship.target is Interface }
                ?: relationship.getOwnerName())
            return prefix + relationship.fieldName.capitalize()
        }

}
