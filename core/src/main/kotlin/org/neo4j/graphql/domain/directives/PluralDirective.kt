package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readRequiredArgument
import kotlin.Annotation

class PluralDirective private constructor(var value: String) : Annotation {

    companion object {
        internal fun create(directive: Directive): PluralDirective {
            return PluralDirective(directive.readRequiredArgument(PluralDirective::value))
        }
    }
}
