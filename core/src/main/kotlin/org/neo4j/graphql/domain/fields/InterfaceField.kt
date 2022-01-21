package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta

class InterfaceField<OWNER: Any>(
    fieldName: String,
    typeMeta: TypeMeta,
    val implementations: List<String>,
) : BaseField<OWNER>(
    fieldName,
    typeMeta,
)
