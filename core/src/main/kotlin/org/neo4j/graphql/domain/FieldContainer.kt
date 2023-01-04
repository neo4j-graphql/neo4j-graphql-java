package org.neo4j.graphql.domain

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.domain.predicates.definitions.AggregationPredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.PredicateDefinition
import org.neo4j.graphql.isList

/**
 * A container holding fields
 */
abstract class FieldContainer<T : BaseField>(val fields: List<T>) {

    init {
        fields.forEach { it.owner = this }
    }

    abstract val name: String

    val hasNonGeneratedProperties: Boolean get() = fields.any { !it.generated }
    val hasRequiredNonGeneratedFields: Boolean get() = fields.any { !it.generated && it.required }
    val hasRequiredFields: Boolean get() = fields.any { it.required }

    val sortableFields: List<BaseField> by lazy {
        fields
            .filterNot { it.typeMeta.type.isList() }
            .filter {
                it is PrimitiveField ||
                        it is CustomScalarField ||
                        it is CustomEnumField ||
                        it is TemporalField ||
                        it is PointField ||
                        it is CypherField
            }

    }

    val scalarFields: List<ScalarField> by lazy {
        fields.filterIsInstance<ScalarField>()
    }

    val relationFields: List<RelationField> by lazy { fields.filterIsInstance<RelationField>() }
    val temporalFields: List<TemporalField> by lazy { fields.filterIsInstance<TemporalField>() }
    val primitiveFields: List<PrimitiveField> by lazy {
        fields.filterIsInstance<PrimitiveField>().filterNot { it is TemporalField }
    }
    val connectionFields: List<ConnectionField> by lazy { fields.filterIsInstance<ConnectionField>() }
    val constrainableFields: List<BaseField> by lazy { fields.filter { it is ConstrainableField } }
    val uniqueFields: List<BaseField> by lazy { constrainableFields.filter { it.unique != null } }
    val computedFields: List<ComputedField> by lazy { fields.filterIsInstance<ComputedField>() }
    val authableFields: List<BaseField> by lazy { fields.filter { it is AuthableField } }

    val predicates: Map<String, PredicateDefinition> by lazy {
        val result = mutableMapOf<String, PredicateDefinition>()
        scalarFields.forEach { result.putAll(it.predicates) }
        relationFields.forEach { result.putAll(it.predicates) }
        result
    }

    val aggregationPredicates: Map<String, AggregationPredicateDefinition> by lazy {
        val result = mutableMapOf<String, AggregationPredicateDefinition>()
        fields.filterIsInstance<PrimitiveField>().forEach { result.putAll(it.aggregationPredicates) }
        result
    }

    val relationAggregationFields: Map<String, RelationField> by lazy {
        relationFields.filter { it.node != null }
            .map { it.fieldName + Constants.AGGREGATION_FIELD_SUFFIX to it }
            .toMap()
    }

    fun getField(name: String): BaseField? {
        // TODO use map instead?
        return fields.find { it.fieldName == name }
    }
}
