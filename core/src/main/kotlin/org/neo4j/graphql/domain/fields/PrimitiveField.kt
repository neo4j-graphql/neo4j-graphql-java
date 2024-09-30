package org.neo4j.graphql.domain.fields

import graphql.language.Type
import graphql.language.Value
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.directives.FieldAnnotations

/**
 * Representation of any field that does not have
 * a cypher directive or relationship directive
 * String, Int, Float, ID, Boolean... (custom scalars).
 */
open class PrimitiveField(
    fieldName: String,
    type: Type<*>,
    annotations: FieldAnnotations,
    schemaConfig: SchemaConfig,
) : ScalarField(
    fieldName,
    type,
    annotations,
    schemaConfig,
), HasDefaultValue, HasCoalesceValue {
    override val defaultValue: Value<*>? get() = annotations.default?.value
    override val coalesceValue: Value<*>? get() = annotations.coalesce?.value
}
