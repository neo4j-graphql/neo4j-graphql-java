package org.neo4j.graphql.domain.fields

import graphql.language.Value
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.TypeMeta

class CustomEnumField(
    fieldName: String,
    typeMeta: TypeMeta,
    schemaConfig: SchemaConfig,
) : ScalarField(
    fieldName,
    typeMeta,
    schemaConfig,
), HasDefaultValue, HasCoalesceValue, AuthableField, MutableField {
    override var defaultValue: Value<*>? = null
    override var coalesceValue: Value<*>? = null
}
