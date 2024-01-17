package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.Annotations

class UnionField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: Annotations,
    val nodes: List<String>,
) : BaseField(
    fieldName,
    typeMeta,
    annotations
), AuthableField, MutableField
