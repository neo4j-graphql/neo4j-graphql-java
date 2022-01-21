package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.Relationship
import org.neo4j.graphql.domain.TypeMeta

class ConnectionField<OWNER: Any>(
    fieldName: String,
    typeMeta: TypeMeta,
    val relationshipField: RelationField<OWNER>
) : BaseField<OWNER>(
    fieldName,
    typeMeta,
) {
    val relationship: Relationship get() = relationshipField.relationship
    val relationshipTypeName: String get() = relationship.name
}
