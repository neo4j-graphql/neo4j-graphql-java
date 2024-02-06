package org.neo4j.graphql.domain.fields

import graphql.language.Value
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.FieldAnnotations

class CustomEnumField(
    fieldName: String,
    typeMeta: TypeMeta,
    annotations: FieldAnnotations,
    schemaConfig: SchemaConfig,
) : ScalarField(
    fieldName,
    typeMeta,
    annotations,
    schemaConfig,
), HasDefaultValue, HasCoalesceValue, AuthableField, MutableField {
    override val defaultValue: Value<*>? get() = annotations.default?.value
    override val coalesceValue: Value<*>? get() = annotations.coalesce?.value
}
