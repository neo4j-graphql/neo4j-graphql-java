package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import graphql.language.Value
import org.neo4j.graphql.readRequiredArgument
import kotlin.Annotation

class DefaultDirective private constructor(
    val value: Value<*>,
) : Annotation {

    companion object {
        internal fun create(directive: Directive): DefaultDirective {
            return DefaultDirective(directive.readRequiredArgument(DefaultDirective::value) { it })
        }
    }
}


