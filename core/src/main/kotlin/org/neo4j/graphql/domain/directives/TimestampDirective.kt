package org.neo4j.graphql.domain.directives

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.DirectiveConstants
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class TimestampDirective(
    val operations: List<TimeStampOperation>,
) {

    enum class TimeStampOperation { CREATE, UPDATE }

    companion object {
        fun create(directive: Directive): TimestampDirective {
            directive.validateName(DirectiveConstants.TIMESTAMP)
            return TimestampDirective(directive.readArgument(TimestampDirective::operations) { arrayValue ->
                (arrayValue as ArrayValue).values.map {
                    TimeStampOperation.valueOf((it as EnumValue).name)
                }
            } ?: TimeStampOperation.values().asList())
        }
    }
}

