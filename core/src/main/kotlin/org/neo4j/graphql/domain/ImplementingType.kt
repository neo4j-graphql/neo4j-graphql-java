package org.neo4j.graphql.domain

import graphql.language.Comment
import graphql.language.Description
import org.neo4j.graphql.domain.directives.ImplementingTypeAnnotations
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.naming.ImplementingTypeNames

sealed class ImplementingType(
    override val name: String,
    val description: Description? = null,
    val comments: List<Comment> = emptyList(),
    fields: List<BaseField>,
    val interfaces: List<Interface>,
    override val annotations: ImplementingTypeAnnotations,
) : FieldContainer<BaseField>(fields), Entity {

    abstract override val namings: ImplementingTypeNames

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Interface) return false

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    fun <UNION_RESULT : RESULT, INTERFACE_RESULT : RESULT, NODE_RESULT : RESULT, RESULT> extractOnImplementingType(
        onNode: (Node) -> NODE_RESULT,
        onInterface: (Interface) -> INTERFACE_RESULT,
    ): RESULT = when (this) {
        is Node -> onNode(this)
        is Interface -> onInterface(this)
    }
}
