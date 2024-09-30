package org.neo4j.graphql.domain.fields

import graphql.language.Type
import org.neo4j.graphql.domain.directives.FieldAnnotations

class InterfaceField(
    fieldName: String,
    type: Type<*>,
    annotations: FieldAnnotations,
    val implementations: List<String>,
) : BaseField(
    fieldName,
    type,
    annotations
)
