package org.neo4j.graphql.domain

import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.domain.predicates.AggregationFieldPredicate
import org.neo4j.graphql.domain.predicates.ConnectionFieldPredicate
import org.neo4j.graphql.domain.predicates.RelationFieldPredicate
import org.neo4j.graphql.domain.predicates.ScalarFieldPredicate
import org.neo4j.graphql.domain.predicates.definitions.AggregationPredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.PredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition
import org.neo4j.graphql.isList
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.toDict

/**
 * A container holding fields
 */
sealed class FieldContainer<T : BaseField>(val fields: List<T>) {

    init {
        fields.forEach { it.owner = this }
    }

    abstract val name: String

    private val fieldsByName = fields.map { it.fieldName to it }.toMap()

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
                        (it is CypherField && it.isSortable())
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
    val uniqueFields: List<BaseField> by lazy { constrainableFields.filter { it.annotations.unique != null } }
    val computedFields: List<ComputedField> by lazy { fields.filterIsInstance<ComputedField>() }
    val authableFields: List<BaseField> by lazy { fields.filter { it is AuthableField } }
    val cypherFields: List<CypherField> by lazy { fields.filterIsInstance<CypherField>() }

    val predicateDefinitions: Map<String, PredicateDefinition> by lazy {
        val result = mutableMapOf<String, PredicateDefinition>()
        scalarFields.forEach { result.putAll(it.predicateDefinitions) }
        relationFields.forEach { result.putAll(it.predicateDefinitions) }
        result
    }

    val aggregationPredicates: Map<String, AggregationPredicateDefinition> by lazy {
        val result = mutableMapOf<String, AggregationPredicateDefinition>()
        fields.filterIsInstance<PrimitiveField>().forEach { result.putAll(it.aggregationPredicates) }
        result
    }

    val relationAggregationFields: Map<String, RelationField> by lazy {
        relationFields.filter { it.target is Node }
            .map { it.namings.aggregateTypeName to it }
            .toMap()
    }

    val updateDefinitions: Map<String, Pair<ScalarField, ScalarField.ScalarUpdateOperation>> by lazy {
        val result = mutableMapOf<String, Pair<ScalarField, ScalarField.ScalarUpdateOperation>>()
        scalarFields.forEach { field ->
            field.updateDefinitions.forEach { (key, op) ->
                result.put(key, field to op)
            }
        }
        result
    }


    fun getField(name: String): BaseField? = fieldsByName[name]

    fun createPredicate(key: String, value: Any?) = predicateDefinitions[key]?.let { def ->
        when (def) {
            is ScalarPredicateDefinition -> ScalarFieldPredicate(def, value)
            is RelationPredicateDefinition -> when (def.connection) {
                true -> ConnectionFieldPredicate(def, value?.let { ConnectionWhere.create(def.field, it) })
                false -> RelationFieldPredicate(def, value?.let { WhereInput.create(def.field, it.toDict()) })
            }

            is AggregationPredicateDefinition -> AggregationFieldPredicate(def, value)
        }
    }
}
