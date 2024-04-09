package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations

class ConnectionField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
    val relationshipField: RelationBaseField
) : BaseField(fieldName, typeMeta, annotations) {

    val properties: RelationshipProperties? get() = (relationshipField as? RelationField)?.properties

    override val dbPropertyName get() = relationshipField.dbPropertyName
}
