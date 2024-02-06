package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations

class InterfaceField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
    val implementations: List<String>,
) : BaseField(
    fieldName,
    typeMeta,
    annotations
), MutableField
