package org.neo4j.graphql.domain.directives

import kotlin.Annotation

class IdDirective private constructor() : Annotation {

    companion object {
        internal val INSTANCE = IdDirective()
    }
}


