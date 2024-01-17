package org.neo4j.graphql.domain.predicates.definitions

import graphql.language.Description
import graphql.language.Directive
import org.atteo.evo.inflector.EnglischInflector
import org.neo4j.graphql.asDescription
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.predicates.RelationOperator
import org.neo4j.graphql.name
import org.neo4j.graphql.toDeprecatedDirective

data class RelationPredicateDefinition(
    override val name: String,
    val field: RelationField,
    val operator: RelationOperator,
    val connection: Boolean
) : PredicateDefinition {

    val description: Description?
        get() = if (operator.list && operator.deprecatedAlternative == null) {
            val plural = (this.field.owner as? ImplementingType)?.pluralKeepCase ?: this.field.getOwnerName()
            val relatedName = if (connection) {
                // TODO really this name?
                EnglischInflector.getPlural(this.field.connectionField.typeMeta.type.name())
            } else this.field.extractOnTarget(
                onImplementingType = { it.pluralKeepCase },
                onUnion = { it.pluralKeepCase }
            )
            "Return $plural where ${if (operator !== RelationOperator.SINGLE) operator.suffix!!.lowercase() else "one"} of the related $relatedName match this filter".asDescription()
        } else {
            null
        }

    val deprecated: Directive?
        get() = if (operator.deprecatedAlternative != null) {
            val baseField = if (connection) {
                this.field.connectionField
            } else {
                this.field
            }
            "Use `${baseField.fieldName}_${operator.deprecatedAlternative}` instead.".toDeprecatedDirective()
        } else {
            null
        }

}
