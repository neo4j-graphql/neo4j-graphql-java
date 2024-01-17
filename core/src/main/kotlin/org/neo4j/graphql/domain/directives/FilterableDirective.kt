package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class FilterableDirective(
    val byValue: Boolean,
    val byAggregate: Boolean,
) {

    companion object {
        const val NAME = "filterable"
        fun create(directive: Directive): FilterableDirective {
            directive.validateName(NAME)
            return FilterableDirective(
                directive.readArgument(FilterableDirective::byValue) ?: true,
                directive.readArgument(FilterableDirective::byAggregate) ?: false,
            )
        }
    }
}


