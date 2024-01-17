package org.neo4j.graphql.domain

import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.domain.naming.BaseNames

sealed interface Entity {
    val name: String
    val annotations: Annotations

    val operations: BaseNames
    val plural: String get() = operations.plural
    val pluralKeepCase: String get() = operations.pluralKeepCase
    val pascalCasePlural: String get() = operations.pascalCasePlural

    fun <UNION_RESULT : RESULT, INTERFACE_RESULT : RESULT, NODE_RESULT : RESULT, RESULT> extractOnTarget(
        onNode: (Node) -> NODE_RESULT,
        onInterface: (Interface) -> INTERFACE_RESULT,
        onUnion: (Union) -> UNION_RESULT,
    ): RESULT = when (this) {
        is Node -> onNode(this)
        is Interface -> onInterface(this)
        is Union -> onUnion(this)
    }

    fun <UNION_RESULT : RESULT, IMPLEMENTATION_TYPE_RESULT : RESULT, RESULT> extractOnTarget(
        onImplementingType: (ImplementingType) -> IMPLEMENTATION_TYPE_RESULT,
        onUnion: (Union) -> UNION_RESULT,
    ): RESULT = extractOnTarget(
        onNode = { onImplementingType(it) },
        onInterface = { onImplementingType(it) },
        onUnion
    )

    companion object {
        @JvmStatic
        fun leadingUnderscores(name: String): String {
            return Regex("^(_+).+").matchEntire(name)?.groupValues?.get(1) ?: "";
        }
    }
}
