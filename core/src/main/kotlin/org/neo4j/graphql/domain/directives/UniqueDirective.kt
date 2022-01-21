package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class UniqueDirective(
    val constraintName: String?,
) {

    companion object {
        fun create(directive: Directive): UniqueDirective {
            directive.validateName(DirectiveConstants.UNIQUE)
            return UniqueDirective(directive.readArgument(UniqueDirective::constraintName))
        }
    }
}

