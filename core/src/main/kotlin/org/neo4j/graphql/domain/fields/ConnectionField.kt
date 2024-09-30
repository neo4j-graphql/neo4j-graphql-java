package org.neo4j.graphql.domain.fields

import graphql.language.Type
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.directives.FieldAnnotations

class ConnectionField(
    fieldName: String,
    type: Type<*>,
    annotations: FieldAnnotations,
    val relationshipField: RelationField
) : BaseField(fieldName, type, annotations) {

    val properties: RelationshipProperties? get() = (relationshipField as? RelationField)?.properties

    override val dbPropertyName get() = relationshipField.dbPropertyName
}
