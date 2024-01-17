package org.neo4j.graphql.domain.directives

import graphql.language.Directive
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

data class CypherDirective(
    val statement: String,
    val columnName: String?,
) {

    companion object {
        const val NAME = "cypher"
        fun create(directive: Directive): CypherDirective {
            directive.validateName(NAME)
            return CypherDirective(
                directive.readRequiredArgument(CypherDirective::statement),
                directive.readArgument(CypherDirective::columnName),
            )
        }
    }
}


