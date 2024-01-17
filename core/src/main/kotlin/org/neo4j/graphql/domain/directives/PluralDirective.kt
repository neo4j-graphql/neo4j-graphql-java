package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

class PluralDirective(var value: String) {

    companion object {
        const val NAME = "plural"
        fun create(directive: Directive): PluralDirective {
            directive.validateName(NAME)
            return PluralDirective(directive.readRequiredArgument(PluralDirective::value))
        }
    }
}
