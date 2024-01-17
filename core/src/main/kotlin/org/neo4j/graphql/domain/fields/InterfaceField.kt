package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.Annotations

class InterfaceField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: Annotations,
    val implementations: List<String>,
) : BaseField(
    fieldName,
    typeMeta,
    annotations
), MutableField
