package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import kotlin.Annotation

class FilterableDirective private constructor(
    val byValue: Boolean,
) : Annotation {

    companion object {
        const val NAME = "filterable"

        internal fun create(directive: Directive): FilterableDirective {
            return FilterableDirective(
                directive.readArgument(FilterableDirective::byValue) ?: true,
            )
        }
    }
}


