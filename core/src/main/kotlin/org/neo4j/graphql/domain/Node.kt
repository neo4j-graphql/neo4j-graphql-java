package org.neo4j.graphql.domain

import graphql.language.Comment
import graphql.language.Description
import graphql.language.Directive
import org.atteo.evo.inflector.English
import org.neo4j.cypherdsl.core.Cypher
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
) : FieldContainer<BaseField>(fields) {

    val mainLabel: String get() = nodeDirective?.label ?: name

    val additionalLabels: List<String> get() = nodeDirective?.additionalLabels ?: emptyList()

    val plural: String by lazy { nodeDirective?.plural ?: English.plural(name).capitalize() }

    fun isOperationAllowed(op: ExcludeDirective.ExcludeOperation) = exclude?.operations?.contains(op) != true

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

