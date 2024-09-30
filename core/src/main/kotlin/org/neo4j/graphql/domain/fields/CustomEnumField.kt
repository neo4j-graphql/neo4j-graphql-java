package org.neo4j.graphql.domain.fields

import graphql.language.Type
import graphql.language.Value
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.directives.FieldAnnotations

class CustomEnumField(
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
