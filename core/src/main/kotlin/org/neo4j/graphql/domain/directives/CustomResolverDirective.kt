package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class CustomResolverDirective(
    val requires: Set<String>?,
) {

    companion object {
        fun create(directive: Directive): CustomResolverDirective {
            directive.validateName(DirectiveConstants.CUSTOM_RESOLVER)
            return CustomResolverDirective(directive.readArgument(CustomResolverDirective::requires))
        }
    }
}

