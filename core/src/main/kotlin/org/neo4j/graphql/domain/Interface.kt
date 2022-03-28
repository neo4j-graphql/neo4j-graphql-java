package org.neo4j.graphql.domain

import graphql.language.Comment
import graphql.language.Description
import graphql.language.Directive
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.fields.BaseField

class Interface(
    name: String,
    description: Description? = null,
    comments: List<Comment> = emptyList(),
    fields: List<BaseField>,
    otherDirectives: List<Directive>,
    interfaces: List<Interface>,
    exclude: ExcludeDirective? = null,
    auth: AuthDirective? = null,
) : ImplementingType(name, description, comments, fields, otherDirectives, interfaces, exclude, auth) {

    var implementations: List<Node> = emptyList()

    override fun toString(): String {
        return "Interface('$name')"
    }
}
