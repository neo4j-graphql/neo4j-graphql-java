package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations

class ConnectionField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
    val relationshipField: RelationField
) : BaseField(fieldName, typeMeta, annotations) {
    val relationshipTypeName: String get() = relationshipField.relationshipTypeName
    val properties: RelationshipProperties? get() = relationshipField.properties

    override val dbPropertyName get() = relationshipField.dbPropertyName
}
