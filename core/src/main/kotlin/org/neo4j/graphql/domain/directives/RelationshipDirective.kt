package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class RelationshipDirective(
    val direction: RelationField.Direction,
    val type: String,
    val properties: String?,
    val queryDirection: RelationField.QueryDirection
) {

    companion object {
        fun create(directive: Directive): RelationshipDirective {
            directive.validateName(DirectiveConstants.RELATIONSHIP)

            val direction =
                (directive.readArgument(RelationshipDirective::direction) { RelationField.Direction.valueOf((it as EnumValue).name) }
                    ?: throw IllegalArgumentException("direction required"))

            val type = (directive.readArgument(RelationshipDirective::type)
                ?: throw IllegalArgumentException("type required"))

            val properties = directive.readArgument(RelationshipDirective::properties)

            val queryDirection =
                directive.readArgument(RelationshipDirective::queryDirection) { RelationField.QueryDirection.valueOf((it as EnumValue).name) }
                    ?: RelationField.QueryDirection.DEFAULT_DIRECTED

            return RelationshipDirective(direction, type, properties, queryDirection)
        }
    }
}


