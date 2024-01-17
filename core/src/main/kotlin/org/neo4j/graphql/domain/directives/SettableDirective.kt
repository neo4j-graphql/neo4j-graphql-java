package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class SettableDirective(
    val onCreate: Boolean,
    val onUpdate: Boolean,
) {

    companion object {
        const val NAME = "settable"
        fun create(directive: Directive): SettableDirective {
            directive.validateName(NAME)
            return SettableDirective(
                directive.readArgument(SettableDirective::onCreate) ?: true,
                directive.readArgument(SettableDirective::onUpdate) ?: true,
            )
        }
    }
}


