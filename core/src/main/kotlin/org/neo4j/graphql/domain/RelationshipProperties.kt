package org.neo4j.graphql.domain

import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField

/**
 * A container holding the fields of a rich relationship (relation with properties)
 */
class RelationshipProperties(
    /**
     * The name of the interface declaring the relationships properties
     */
    val typeName: String,
    /**
     * the fields of the rich relation
     */
    fields: List<ScalarField>
) : FieldContainer<ScalarField>(fields) {
    override val name: String get() = typeName
    private val mutableUsedByRelations: MutableList<RelationField> = mutableListOf()
    val usedByRelations: List<RelationField> get() = mutableUsedByRelations

    fun addUsedByRelation(relation: RelationField) {
        mutableUsedByRelations.add(relation)
    }
}
