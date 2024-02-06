package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations

/**
 * Representation of the `@computed` directive and its meta.
 */
class ComputedField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
) : BaseField(
    fieldName, typeMeta, annotations
)
