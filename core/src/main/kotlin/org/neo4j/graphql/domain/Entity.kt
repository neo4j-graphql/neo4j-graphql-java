package org.neo4j.graphql.domain

import org.neo4j.graphql.domain.directives.EntityAnnotations
import org.neo4j.graphql.domain.naming.EntityNames

sealed interface Entity {
    val name: String
    val annotations: EntityAnnotations

    val namings: EntityNames
    val plural: String get() = namings.plural
    val pluralKeepCase: String get() = namings.pluralKeepCase
    val pascalCasePlural: String get() = namings.pascalCasePlural

    val schema: Schema

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
