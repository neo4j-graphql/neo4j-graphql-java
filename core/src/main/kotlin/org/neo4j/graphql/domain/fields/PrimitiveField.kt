package org.neo4j.graphql.domain.fields

import graphql.language.Value
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.isRequired
import org.neo4j.graphql.name

/**
 * Representation of any field thats not
 * a cypher directive or relationship directive
 * String, Int, Float, ID, Boolean... (custom scalars).
 */
open class PrimitiveField(
    fieldName: String,
    typeMeta: TypeMeta,
    schemaConfig: SchemaConfig,
) : ScalarField(
    fieldName,
    typeMeta,
    schemaConfig,
), ConstrainableField, HasDefaultValue, HasCoalesceValue, AuthableField, MutableField {
    var autogenerate: Boolean = false
    override var defaultValue: Value<*>? = null
    override var coalesceValue: Value<*>? = null

    override val generated: Boolean get() = super.generated || autogenerate

    fun getAggregationSelectionLibraryTypeName(): String {
        val suffix = if (typeMeta.type.isRequired()) "NonNullable" else "Nullable"
        return "${typeMeta.type.name()}AggregateSelection$suffix"
    }
}
