package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import kotlin.Annotation

class QueryDirective private constructor(
    val read: Boolean,
) : Annotation {

    companion object {
        internal fun create(directive: Directive): QueryDirective {
            return QueryDirective(
                directive.readArgument(QueryDirective::read) ?: true,
            )
        }
    }
}


