package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

data class AliasDirective(
    val property: String,
) {

    companion object {
        const val NAME = "alias"

        fun create(directive: Directive): AliasDirective {
            directive.validateName(NAME)
            return AliasDirective(directive.readRequiredArgument(AliasDirective::property))
        }
    }
}


