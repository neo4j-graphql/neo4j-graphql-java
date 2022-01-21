package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import graphql.language.Value
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

data class CoalesceDirective(
    val value: Value<*>,
) {

    companion object {
        fun create(directive: Directive): CoalesceDirective {
            directive.validateName(DirectiveConstants.COALESCE)
            return CoalesceDirective(directive.readRequiredArgument(CoalesceDirective::value) { it })
        }
    }
}


