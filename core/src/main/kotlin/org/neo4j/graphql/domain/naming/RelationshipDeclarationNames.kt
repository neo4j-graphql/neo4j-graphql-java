package org.neo4j.graphql.domain.naming

import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.directives.FieldAnnotations
import org.neo4j.graphql.domain.fields.RelationDeclarationField

class RelationshipDeclarationNames(
    relationship: RelationDeclarationField,
    annotations: FieldAnnotations
) : RelationshipBaseNames<RelationDeclarationField>(relationship, annotations) {

    override val edgePrefix get() = "${prefixForTypename}Edge"

    override val fieldInputPrefixForTypename
        get() = relationship.getOwnerName() + relationship.fieldName.capitalize()

    val relationshipPropertiesFieldTypename get() = "${relationshipFieldTypename}Properties"
}
