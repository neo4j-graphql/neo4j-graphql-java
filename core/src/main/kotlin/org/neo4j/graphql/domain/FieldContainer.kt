package org.neo4j.graphql.domain

import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.isList

/**
 * A container holding fields
 */
abstract class FieldContainer<T : BaseField>(val fields: List<T>) {

    init {
        fields.forEach { it.owner = this }
    }

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
    val connectionFields: List<ConnectionField> by lazy { fields.filterIsInstance<ConnectionField>() }
    val constrainableFields: List<BaseField> by lazy { fields.filter { it is ConstrainableField } }
    val uniqueFields: List<BaseField> by lazy { constrainableFields.filter { it.unique != null } }
}
