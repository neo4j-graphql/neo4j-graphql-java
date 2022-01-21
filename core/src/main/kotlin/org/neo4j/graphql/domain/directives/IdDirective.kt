package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class IdDirective(
    val autogenerate: Boolean,
    val unique: Boolean,
) {

    companion object {
        fun create(directive: Directive): IdDirective {
            directive.validateName(DirectiveConstants.ID)
            return IdDirective(
                directive.readArgument(IdDirective::autogenerate) ?: true,
                // If unique argument is absent from @id directive, default is to use unique constraint
                directive.readArgument(IdDirective::unique) ?: true
            )
        }
    }
}


