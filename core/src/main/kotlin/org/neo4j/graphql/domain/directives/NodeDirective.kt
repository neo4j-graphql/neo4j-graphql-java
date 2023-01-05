package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

class NodeDirective(
    var label: String? = null,
    var additionalLabels: List<String>? = null,
) {

    companion object {
        fun create(directive: Directive): NodeDirective {
            directive.validateName(DirectiveConstants.NODE)
            return NodeDirective(
                directive.readArgument(NodeDirective::label),
                directive.readArgument(NodeDirective::additionalLabels),
            )
        }
    }
}
