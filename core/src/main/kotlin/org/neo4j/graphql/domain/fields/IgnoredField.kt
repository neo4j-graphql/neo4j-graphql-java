package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta

class IgnoredField<OWNER: Any>(
    fieldName: String,
    typeMeta: TypeMeta,
) : BaseField<OWNER>(
    fieldName,
    typeMeta,
)
