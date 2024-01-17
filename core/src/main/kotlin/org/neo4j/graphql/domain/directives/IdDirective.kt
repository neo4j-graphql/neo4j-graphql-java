package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.validateName

class IdDirective private constructor() {

    companion object {
        const val NAME = "id"

        private val INSTANCE = IdDirective()

        fun create(directive: Directive): IdDirective {
            directive.validateName(NAME)
            return INSTANCE
        }
    }
}


