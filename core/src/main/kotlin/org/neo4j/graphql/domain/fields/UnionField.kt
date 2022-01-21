package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta

class UnionField<OWNER: Any>(
    fieldName: String,
    typeMeta: TypeMeta,
    val nodes: List<String>,
) : BaseField<OWNER>(
    fieldName,
    typeMeta,
)
