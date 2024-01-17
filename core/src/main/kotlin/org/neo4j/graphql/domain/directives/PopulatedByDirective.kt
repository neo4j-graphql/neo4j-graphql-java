package org.neo4j.graphql.domain.directives

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.readRequiredArgument
import org.neo4j.graphql.validateName

data class PopulatedByDirective(
    val callback: String,
    val operations: Set<PopulatedByOperation>,
) {

    enum class PopulatedByOperation {
        CREATE, UPDATE
    }

    companion object {
        const val NAME = "populatedBy"
        fun create(directive: Directive): PopulatedByDirective {
            directive.validateName(NAME)
            return PopulatedByDirective(
                directive.readRequiredArgument(PopulatedByDirective::callback),
                directive.readArgument(PopulatedByDirective::operations) { arrayValue ->
                    (arrayValue as ArrayValue).values.map {
                        PopulatedByOperation.valueOf((it as EnumValue).name)
                    }
                }?.toSet() ?: PopulatedByOperation.values().toSet()
            )
        }
    }
}

