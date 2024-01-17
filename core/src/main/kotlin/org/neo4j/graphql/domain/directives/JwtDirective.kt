package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.validateName

class JwtDirective private constructor() {

    companion object {
        const val NAME = "jwt"

        private val INSTANCE = JwtDirective()

        fun create(directive: Directive): JwtDirective {
            directive.validateName(NAME)
            return INSTANCE
        }
    }
}


