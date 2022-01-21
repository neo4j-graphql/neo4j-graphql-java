package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.TypeMeta

class ConnectionField(
    fieldName: String,
    typeMeta: TypeMeta,
    val relationshipField: RelationField
) : BaseField(
    fieldName,
    typeMeta,
) {
    val relationshipTypeName: String get() = relationshipField.name
    val properties: RelationshipProperties? get() = relationshipField.properties
}
