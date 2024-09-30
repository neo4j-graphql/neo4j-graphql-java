package org.neo4j.graphql.domain

import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.domain.predicates.ConnectionFieldPredicate
import org.neo4j.graphql.domain.predicates.RelationFieldPredicate
import org.neo4j.graphql.domain.predicates.ScalarFieldPredicate
import org.neo4j.graphql.domain.predicates.definitions.PredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.RelationPredicateDefinition
import org.neo4j.graphql.domain.predicates.definitions.ScalarPredicateDefinition
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

    val sortableFields: List<BaseField> by lazy {
        fields
            .filterNot { it.isList() }
            .filter {
                it is PrimitiveField ||
                        it is CustomScalarField ||
                        it is CustomEnumField ||
                        it is PointField
            }

    }

    private val scalarFields: List<ScalarField> by lazy {
        fields.filterIsInstance<ScalarField>()
    }

    val relationBaseFields: List<RelationBaseField> by lazy { fields.filterIsInstance<RelationBaseField>() }
    val relationFields: List<RelationField> by lazy { fields.filterIsInstance<RelationField>() }

    private val predicateDefinitions: Map<String, PredicateDefinition> by lazy {
        val result = mutableMapOf<String, PredicateDefinition>()
        scalarFields.forEach { result.putAll(it.predicateDefinitions) }
        relationBaseFields.forEach { result.putAll(it.predicateDefinitions) }
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
        }
    }
}
