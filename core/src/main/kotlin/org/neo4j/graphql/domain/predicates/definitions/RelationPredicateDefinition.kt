package org.neo4j.graphql.domain.predicates.definitions

import graphql.language.Description
import org.atteo.evo.inflector.EnglischInflector
import org.neo4j.graphql.asDescription
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.domain.predicates.RelationOperator

data class RelationPredicateDefinition(
    override val name: String,
    val field: RelationBaseField,
    val operator: RelationOperator,
    val connection: Boolean
) : PredicateDefinition {

    val description: Description?
        get() = if (operator.list) {
            val plural = (this.field.owner as? ImplementingType)?.pluralKeepCase ?: this.field.getOwnerName()
            val relatedName = if (connection) {
                // TODO really this name?
                EnglischInflector.getPlural(this.field.namings.connectionFieldTypename)
            } else this.field.extractOnTarget(
                onImplementingType = { it.pluralKeepCase },
                onUnion = { it.pluralKeepCase }
            )
            "Return $plural where ${if (operator !== RelationOperator.SINGLE) operator.suffix!!.lowercase() else "one"} of the related $relatedName match this filter".asDescription()
        } else {
            null
        }
}
