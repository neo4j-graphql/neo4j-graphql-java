package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

class RelationshipDirective(
    val direction: RelationField.Direction,
    val type: String,
    val properties: String?,
    val queryDirection: RelationField.QueryDirection,
    nestedOperations: Set<RelationshipNestedOperations>,
    aggregate: Boolean,
) : RelationshipBaseDirective(nestedOperations, aggregate) {

    companion object {
        const val NAME = "relationship"
        fun create(directive: Directive): RelationshipDirective {
            directive.validateName(NAME)

            val direction =
                directive.readRequiredArgument(RelationshipDirective::direction) { RelationField.Direction.valueOf((it as EnumValue).name) }

            val type = directive.readRequiredArgument(RelationshipDirective::type)

            val properties = directive.readArgument(RelationshipDirective::properties)

            val queryDirection =
                directive.readArgument(RelationshipDirective::queryDirection) { RelationField.QueryDirection.valueOf((it as EnumValue).name) }
                    ?: RelationField.QueryDirection.DEFAULT_DIRECTED

            val nestedOperations = extractNestedOperations(directive)
            val aggregate = directive.readArgument(RelationshipDirective::aggregate) ?: true

            return RelationshipDirective(direction, type, properties, queryDirection, nestedOperations, aggregate)
        }
    }
}


