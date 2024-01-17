package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class UniqueDirective(
    val constraintName: String?,
) {

    companion object {
        const val NAME = "unique"
        fun create(directive: Directive): UniqueDirective {
            directive.validateName(NAME)
            return UniqueDirective(directive.readArgument(UniqueDirective::constraintName))
        }
    }
}

