package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta

class IgnoredField(
    fieldName: String,
    typeMeta: TypeMeta,
) : BaseField(
    fieldName,
    typeMeta,
)
