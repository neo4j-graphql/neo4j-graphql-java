package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

class NodeDirective(
    var labels: List<String>? = null,
) {

    companion object {
        const val NAME = "node"
        fun create(directive: Directive): NodeDirective {
            directive.validateName(NAME)
            return NodeDirective(
                directive.readArgument(NodeDirective::labels),
            )
        }
    }
}
