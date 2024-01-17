package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class CustomResolverDirective(
    val requires: String?,
) {

    companion object {
        const val NAME = "customResolver"
        fun create(directive: Directive): CustomResolverDirective {
            directive.validateName(NAME)
            return CustomResolverDirective(directive.readArgument(CustomResolverDirective::requires))
        }
    }
}

