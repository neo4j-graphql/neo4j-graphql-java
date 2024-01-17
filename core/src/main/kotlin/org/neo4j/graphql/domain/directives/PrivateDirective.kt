package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.validateName

class PrivateDirective private constructor() {

    companion object {
        const val NAME = "private"

        private val INSTANCE = PrivateDirective()

        fun create(directive: Directive): PrivateDirective {
            directive.validateName(NAME)
            return INSTANCE
        }
    }
}


