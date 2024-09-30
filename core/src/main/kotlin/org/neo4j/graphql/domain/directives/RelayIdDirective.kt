package org.neo4j.graphql.domain.directives

import kotlin.Annotation

class RelayIdDirective private constructor() : Annotation {

    companion object {
        internal val INSTANCE = RelayIdDirective()
    }
}


