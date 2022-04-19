package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta

class PointField(
    fieldName: String,
    typeMeta: TypeMeta,
) : ScalarField(
    fieldName,
    typeMeta,
), ConstrainableField, AuthableField, MutableField
