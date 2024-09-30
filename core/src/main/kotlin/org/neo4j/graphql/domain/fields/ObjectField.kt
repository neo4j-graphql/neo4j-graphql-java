package org.neo4j.graphql.domain.fields

import graphql.language.Type
import org.neo4j.graphql.domain.directives.FieldAnnotations

class ObjectField(
    fieldName: String,
    type: Type<*>,
    annotations: FieldAnnotations,
) : BaseField(
    fieldName,
    type,
    annotations
)
