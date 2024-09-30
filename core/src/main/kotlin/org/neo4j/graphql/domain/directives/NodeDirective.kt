package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import kotlin.Annotation

class NodeDirective private constructor(
    var labels: List<String>? = null,
) : Annotation {

    companion object {
        internal fun create(directive: Directive): NodeDirective {
            return NodeDirective(
                directive.readArgument(NodeDirective::labels),
            )
        }
    }
}
