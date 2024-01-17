package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.Annotations

class ObjectField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: Annotations,
) : BaseField(
    fieldName,
    typeMeta,
    annotations
), AuthableField, MutableField
