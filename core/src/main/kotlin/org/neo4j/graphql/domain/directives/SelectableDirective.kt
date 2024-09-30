package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readRequiredArgument
import kotlin.Annotation

class SelectableDirective private constructor(
    val onRead: Boolean,
    val onAggregate: Boolean,
) : Annotation {

    companion object {
        const val NAME = "selectable"

        internal fun create(directive: Directive): SelectableDirective {
            return SelectableDirective(
                directive.readRequiredArgument(SelectableDirective::onRead),
                directive.readRequiredArgument(SelectableDirective::onAggregate)
            )
        }
    }
}


