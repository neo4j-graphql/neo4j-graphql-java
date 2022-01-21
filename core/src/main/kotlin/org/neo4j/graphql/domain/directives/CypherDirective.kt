package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

data class CypherDirective(
    val statement: String,
) {

    companion object {
        fun create(directive: Directive): CypherDirective {
            directive.validateName(DirectiveConstants.CYPHER)
            return CypherDirective(directive.readRequiredArgument(CypherDirective::statement))
        }
    }
}


