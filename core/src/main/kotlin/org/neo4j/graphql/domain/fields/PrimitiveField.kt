package org.neo4j.graphql.domain.fields

import graphql.language.Value
import org.neo4j.graphql.domain.TypeMeta

/**
 * Representation of any field thats not
 * a cypher directive or relationship directive
 * String, Int, Float, ID, Boolean... (custom scalars).
 */
open class PrimitiveField(
    fieldName: String,
    typeMeta: TypeMeta,
) : ScalarField(
    fieldName,
    typeMeta,
), ConstrainableField{
    var autogenerate: Boolean = false
    var defaultValue: Value<*>? = null
    var coalesceValue: Value<*>? = null

    override val generated: Boolean get()  = super.generated || autogenerate
}
