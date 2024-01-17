package org.neo4j.graphql.domain.directives

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class TimestampDirective(
    val operations: Set<TimeStampOperation>,
) {

    enum class TimeStampOperation { CREATE, UPDATE }

    companion object {
        const val NAME = "timestamp"
        fun create(directive: Directive): TimestampDirective {
            directive.validateName(NAME)
            return TimestampDirective(directive.readArgument(TimestampDirective::operations) { arrayValue ->
                (arrayValue as ArrayValue).values.map {
                    TimeStampOperation.valueOf((it as EnumValue).name)
                }
            }?.toSet() ?: TimeStampOperation.values().toSet())
        }
    }
}

