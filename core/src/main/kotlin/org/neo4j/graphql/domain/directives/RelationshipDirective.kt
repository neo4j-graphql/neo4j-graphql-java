package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.domain.Relationship
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class RelationshipDirective(
    val direction: Relationship.Direction,
    val type: String,
    val properties: String?
) {

    companion object {
        fun create(directive: Directive): RelationshipDirective {
            directive.validateName(DirectiveConstants.RELATIONSHIP)
            return RelationshipDirective(
                directive.readArgument(RelationshipDirective::direction) { Relationship.Direction.valueOf((it as EnumValue).name) }
                    ?: throw IllegalArgumentException("direction required"),
                directive.readArgument(RelationshipDirective::type)
                    ?: throw IllegalArgumentException("type required"),
                directive.readArgument(RelationshipDirective::properties),
            )
        }
    }
}


