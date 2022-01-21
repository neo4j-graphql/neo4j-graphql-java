package org.neo4j.graphql.domain

import graphql.language.Comment
import graphql.language.Description
import graphql.language.Directive
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.fields.BaseField

class Interface(
    val name: String,
    val description: Description? = null,
    val comments: List<Comment> = emptyList(),
    fields: List<BaseField<Interface>>,
    val otherDirectives: List<Directive>,
    val interfaces: List<Interface>,
    val exclude: ExcludeDirective? = null,
    val auth: AuthDirective? = null,
) : FieldContainer<BaseField<Interface>, Interface>(fields) {

    var implementations: List<Node> = emptyList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Interface) return false

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }


}
