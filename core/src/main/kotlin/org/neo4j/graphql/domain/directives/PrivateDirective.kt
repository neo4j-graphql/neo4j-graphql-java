package org.neo4j.graphql.domain.directives

import kotlin.Annotation

class PrivateDirective private constructor() : Annotation {

    companion object {
        internal val INSTANCE = PrivateDirective()
    }
}


