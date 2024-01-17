package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.validateName

class RelationshipPropertiesDirective private constructor() {

    companion object {
        const val NAME = "relationshipProperties"

        private val INSTANCE = RelationshipPropertiesDirective()

        fun create(directive: Directive): RelationshipPropertiesDirective {
            directive.validateName(NAME)
            return INSTANCE
        }
    }
}


