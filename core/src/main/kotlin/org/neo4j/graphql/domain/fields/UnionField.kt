package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta

class UnionField(
    fieldName: String,
    typeMeta: TypeMeta,
    val nodes: List<String>,
) : BaseField(
    fieldName,
    typeMeta,
), AuthableField, MutableField
