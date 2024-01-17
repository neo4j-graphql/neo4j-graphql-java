package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class QueryDirective(
    val read: Boolean,
    val aggregate: Boolean,
) {

    companion object {
        const val NAME = "query"
        fun create(directive: Directive): QueryDirective {
            directive.validateName(NAME)
            return QueryDirective(
                directive.readArgument(QueryDirective::read) ?: true,
                directive.readArgument(QueryDirective::aggregate) ?: false,
            )
        }
    }
}


