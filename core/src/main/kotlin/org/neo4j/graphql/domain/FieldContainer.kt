package org.neo4j.graphql.domain

import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.isList

/**
 * A container holding fields
 */
abstract class FieldContainer<T : BaseField<OWNER>, OWNER: FieldContainer<T, OWNER>>(
    val fields: List<T>
) {

    init {
        fields.forEach {
            @Suppress("UNCHECKED_CAST")
            it.owner = this as OWNER
        }
    }
    val hasNonGeneratedProperties: Boolean get() = fields.any { !it.generated }
    val hasRequiredNonGeneratedFields: Boolean get() = fields.any { !it.generated && it.required }
    val hasRequiredFields: Boolean get() = fields.any { it.required }

    val sortableFields: List<BaseField<OWNER>> by lazy {
        fields
            .filterNot { it.typeMeta.type.isList() }
            .filter {
                it is PrimitiveField<*> ||
                        it is CustomScalarField<*> ||
                        it is CustomEnumField<*> ||
                        it is TemporalField<*> ||
                        it is PointField<*> ||
                        it is CypherField<*>
            }

    }

    val scalarFields: List<ScalarField<OWNER>> by lazy {
        fields.filterIsInstance<ScalarField<OWNER>>()
    }

    val relationFields: List<RelationField<OWNER>> by lazy { fields.filterIsInstance<RelationField<OWNER>>() }
    val connectionFields: List<ConnectionField<OWNER>> by lazy { fields.filterIsInstance<ConnectionField<OWNER>>() }
    val constrainableFields: List<BaseField<OWNER>> by lazy { fields.filter { it is ConstrainableField } }
    val uniqueFields: List<BaseField<OWNER>> by lazy { constrainableFields.filter { it.unique != null } }
}
