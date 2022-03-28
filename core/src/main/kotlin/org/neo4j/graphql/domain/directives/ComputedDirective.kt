package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class ComputedDirective(
    val from: Set<String>?,
) {

    companion object {
        fun create(directive: Directive): ComputedDirective {
            directive.validateName(DirectiveConstants.COMPUTED)
            return ComputedDirective(directive.readArgument(ComputedDirective::from))
        }
    }
}

