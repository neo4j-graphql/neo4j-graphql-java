package org.neo4j.graphql.domain.directives

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.readArgument
import org.neo4j.graphql.validateName

data class MutationDirective(
    val operations: Set<MutationFields>,
) {
    enum class MutationFields { CREATE, UPDATE, DELETE }

    val create get() = operations.contains(MutationFields.CREATE)
    val update get() = operations.contains(MutationFields.UPDATE)
    val delete get() = operations.contains(MutationFields.DELETE)

    companion object {
        const val NAME = "mutation"
        fun create(directive: Directive): MutationDirective {
            directive.validateName(NAME)
            return MutationDirective(
                directive.readArgument(MutationDirective::operations) { arrayValue ->
                    (arrayValue as ArrayValue).values.map {
                        MutationFields.valueOf((it as EnumValue).name)
                    }
                }?.toSet() ?: MutationFields.values().toSet()
            )
        }
    }
}


