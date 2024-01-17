package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.validateName

class RelayIdDirective private constructor() {

    companion object {
        const val NAME = "relayId"

        private val INSTANCE = RelayIdDirective()
        fun create(directive: Directive): RelayIdDirective {
            directive.validateName(NAME)
            return INSTANCE
        }
    }
}


