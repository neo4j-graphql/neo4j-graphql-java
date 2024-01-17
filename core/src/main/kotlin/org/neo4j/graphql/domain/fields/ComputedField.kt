package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.Annotations

/**
 * Representation of the `@computed` directive and its meta.
 */
class ComputedField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: Annotations,
) : BaseField(
    fieldName, typeMeta, annotations
)
