package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

data class AliasDirective(
    val property: String,
) {

    companion object {
        fun create(directive: Directive): AliasDirective {
            directive.validateName(DirectiveConstants.ALIAS)
            return AliasDirective(directive.readRequiredArgument(AliasDirective::property))
        }
    }
}


