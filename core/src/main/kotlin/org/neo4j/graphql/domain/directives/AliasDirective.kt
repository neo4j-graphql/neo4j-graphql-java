package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readRequiredArgument

class AliasDirective private constructor(
    val property: String,
) : Annotation {

    companion object {
        internal fun create(directive: Directive): AliasDirective {
            return AliasDirective(directive.readRequiredArgument(AliasDirective::property))
        }
    }
}


