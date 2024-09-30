package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.readRequiredArgument

class RelationshipDirective private constructor(
    val direction: RelationField.Direction,
    val type: String,
    val properties: String?,
    val queryDirection: RelationField.QueryDirection,
) : RelationshipBaseDirective(), Annotation {

    companion object {
        const val NAME = "relationship"

        internal fun create(directive: Directive): RelationshipDirective {
            val direction =
                directive.readRequiredArgument(RelationshipDirective::direction) { RelationField.Direction.valueOf((it as EnumValue).name) }

            val type = directive.readRequiredArgument(RelationshipDirective::type)

            val properties = directive.readArgument(RelationshipDirective::properties)

            val queryDirection =
                directive.readArgument(RelationshipDirective::queryDirection) { RelationField.QueryDirection.valueOf((it as EnumValue).name) }
                    ?: RelationField.QueryDirection.DEFAULT_DIRECTED

            return RelationshipDirective(direction, type, properties, queryDirection)
        }
    }
}


