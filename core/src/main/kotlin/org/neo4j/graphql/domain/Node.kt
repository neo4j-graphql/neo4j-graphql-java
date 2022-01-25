package org.neo4j.graphql.domain

import graphql.language.Comment
import graphql.language.Description
import graphql.language.Directive
import org.atteo.evo.inflector.English
import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.directives.FullTextDirective
import org.neo4j.graphql.domain.directives.NodeDirective
import org.neo4j.graphql.domain.fields.BaseField

class Node(
    val name: String,
    val description: Description? = null,
    val comments: List<Comment> = emptyList(),
    fields: List<BaseField>,
    val otherDirectives: List<Directive>,
    val interfaces: List<Interface>,
    val exclude: ExcludeDirective? = null,
    val nodeDirective: NodeDirective? = null,
    val fulltextDirective: FullTextDirective? = null,
    val auth: AuthDirective? = null,
) : FieldContainer<BaseField, Node>(fields) {

    val plural: String by lazy { nodeDirective?.plural ?: English.plural(name).capitalize() }

    fun isOperationAllowed(op: ExcludeDirective.ExcludeOperation) = exclude?.operations?.contains(op) != true
}

