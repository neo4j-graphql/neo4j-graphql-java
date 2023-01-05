package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

class PluralDirective(var value: String) {

    companion object {
        fun create(directive: Directive): PluralDirective {
            directive.validateName(DirectiveConstants.PLURAL)
            return PluralDirective(directive.readRequiredArgument(PluralDirective::value))
        }
    }
}
