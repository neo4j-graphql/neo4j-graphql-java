package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations

class ObjectField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
) : BaseField(
    fieldName,
    typeMeta,
    annotations
), AuthableField, MutableField
