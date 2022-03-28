package org.neo4j.graphql.domain

import graphql.language.Comment
import graphql.language.Description
import graphql.language.Directive
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.directives.FullTextDirective
import org.neo4j.graphql.domain.directives.NodeDirective
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.utils.CamelCaseUtils.camelCase

class Node(
    name: String,
    description: Description? = null,
    comments: List<Comment> = emptyList(),
    fields: List<BaseField>,
    otherDirectives: List<Directive>,
    interfaces: List<Interface>,
    exclude: ExcludeDirective? = null,
    val nodeDirective: NodeDirective? = null,
    val fulltextDirective: FullTextDirective? = null,
    auth: AuthDirective? = null,
) : ImplementingType(name, description, comments, fields, otherDirectives, interfaces, exclude, auth) {

    val mainLabel: String get() = nodeDirective?.label ?: name

    val additionalLabels: List<String> get() = nodeDirective?.additionalLabels ?: emptyList()

    override val plural: String by lazy {
        nodeDirective?.plural?.let { leadingUnderscores(it) + camelCase(it) } ?: super.plural
    }

    override fun toString(): String {
        return "Node('$name')"
    }

    fun asCypherNode(name: String? = null) = Cypher.node(mainLabel, additionalLabels).let {
        when {
            name != null -> it.named(name)
            else -> it
        }
    }

}

