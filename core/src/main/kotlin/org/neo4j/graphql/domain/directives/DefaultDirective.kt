package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import graphql.language.Value
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

data class DefaultDirective(
    val value: Value<*>,
) {

    companion object {
        const val NAME = "default"
        fun create(directive: Directive): DefaultDirective {
            directive.validateName(NAME)
            return DefaultDirective(directive.readRequiredArgument(DefaultDirective::value) { it })
        }
    }
}


