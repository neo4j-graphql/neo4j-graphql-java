package org.neo4j.graphql.domain.fields

import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.name

/**
 * Representation of the `@computed` directive and its meta.
 */
class ComputedField(
    fieldName: String,
    typeMeta: TypeMeta,
    val requiredFields: Set<String>?
) : BaseField(
    fieldName, typeMeta
)
