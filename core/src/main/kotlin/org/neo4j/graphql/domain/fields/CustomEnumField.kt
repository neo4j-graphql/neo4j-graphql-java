package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta

class CustomEnumField<OWNER: Any>(
    fieldName: String,
    typeMeta: TypeMeta,
) : ScalarField<OWNER>(
    fieldName,
    typeMeta,
)
