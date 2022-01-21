package org.neo4j.graphql.domain.directives

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

class ExcludeDirective(
    val operations: List<ExcludeOperation>

) {
    enum class ExcludeOperation {
        CREATE, READ, UPDATE, DELETE
    }

    companion object {
        fun create(directive: Directive): ExcludeDirective {
            directive.validateName(DirectiveConstants.EXCLUDE)
            return ExcludeDirective(
                directive.readArgument(ExcludeDirective::operations) { value ->
                    (value as ArrayValue).values.map { ExcludeOperation.valueOf((it as EnumValue).name) }
                } ?: ExcludeOperation.values().asList()
            )
        }
    }

}
