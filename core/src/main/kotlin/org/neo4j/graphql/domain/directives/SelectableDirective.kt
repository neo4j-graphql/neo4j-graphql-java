package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

data class SelectableDirective(
    val onRead: Boolean,
    val onAggregate: Boolean,
) {

    companion object {
        const val NAME = "selectable"
        fun create(directive: Directive): SelectableDirective {
            directive.validateName(NAME)
            return SelectableDirective(
                directive.readRequiredArgument(SelectableDirective::onRead),
                directive.readRequiredArgument(SelectableDirective::onAggregate)
            )
        }
    }
}


