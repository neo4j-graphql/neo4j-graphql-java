package org.neo4j.graphql.domain.directives

import kotlin.Annotation

class RelationshipPropertiesDirective private constructor() : Annotation {

    companion object {
        internal val INSTANCE = RelationshipPropertiesDirective()
    }
}


