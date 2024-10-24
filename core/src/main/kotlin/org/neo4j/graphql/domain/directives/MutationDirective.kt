package org.neo4j.graphql.domain.directives

import graphql.language.ArrayValue
import graphql.language.Directive
import graphql.language.EnumValue
import org.neo4j.graphql.readArgument
import kotlin.Annotation

class MutationDirective private constructor(
    val operations: Set<MutationFields>,
) : Annotation {
    enum class MutationFields { DELETE }

    val delete get() = operations.contains(MutationFields.DELETE)

    companion object {
        internal fun create(directive: Directive): MutationDirective {
            return MutationDirective(
                directive.readArgument(MutationDirective::operations) { arrayValue ->
                    (arrayValue as ArrayValue).values.map {
                        MutationFields.valueOf((it as EnumValue).name)
                    }
                }?.toSet() ?: MutationFields.entries.toSet()
            )
        }
    }
}


