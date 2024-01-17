package org.neo4j.graphql.domain.fields

import graphql.language.Value
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.domain.TypeMeta
import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.domain.directives.PopulatedByDirective
import org.neo4j.graphql.domain.predicates.definitions.AggregationPredicateDefinition
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
    annotations: Annotations,
    schemaConfig: SchemaConfig,
) : ScalarField(
    fieldName,
    typeMeta,
    annotations,
    schemaConfig,
), ConstrainableField, HasDefaultValue, HasCoalesceValue, AuthableField, MutableField {
    var autogenerate: Boolean = false
    override val defaultValue: Value<*>? get() = annotations.default?.value
    override val coalesceValue: Value<*>? get() = annotations.coalesce?.value

    override val generated: Boolean get() = super.generated || autogenerate || callback != null

    // TODO rename to populatedBY
    var callback: PopulatedByDirective? = annotations.populatedBy

    val aggregationPredicates: Map<String, AggregationPredicateDefinition> by lazy {
        AggregationPredicateDefinition.create(this)
    }

    fun getAggregationSelectionLibraryTypeName(): String {
        val suffix = if (typeMeta.type.isRequired()) "NonNullable" else "Nullable"
        return "${typeMeta.type.name()}AggregateSelection$suffix"
    }
}
