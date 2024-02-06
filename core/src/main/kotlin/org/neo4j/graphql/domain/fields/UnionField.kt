package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations

class UnionField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
    val nodes: List<String>,
) : BaseField(
    fieldName,
    typeMeta,
    annotations
), AuthableField, MutableField
