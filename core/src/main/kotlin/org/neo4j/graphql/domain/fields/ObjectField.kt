package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta

class ObjectField<OWNER: Any>(
    fieldName: String,
    typeMeta: TypeMeta,
) : BaseField<OWNER>(
    fieldName,
    typeMeta,
)
