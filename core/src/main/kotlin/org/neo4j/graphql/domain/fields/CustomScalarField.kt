package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta

class CustomScalarField(
    fieldName: String,
    typeMeta: TypeMeta,
) : ScalarField(
    fieldName,
    typeMeta,
)
