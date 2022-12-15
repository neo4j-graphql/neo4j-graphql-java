package org.neo4j.graphql.domain

import graphql.language.Comment
import graphql.language.Description
import graphql.language.Directive
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.domain.directives.*
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.handler.utils.ChainString
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
    val queryOptions: QueryOptionsDirective? = null,
    auth: AuthDirective? = null,
) : ImplementingType(name, description, comments, fields, otherDirectives, interfaces, exclude, auth) {

    val mainLabel: String get() = nodeDirective?.label ?: name

    val additionalLabels: List<String> get() = nodeDirective?.additionalLabels ?: emptyList()

    val typeNames by lazy { TypeNames() }

    override val plural: String by lazy {
        nodeDirective?.plural?.let { leadingUnderscores(it) + camelCase(it) } ?: super.plural
    }

    inner class TypeNames {
        val createResponse = "Create${pascalCasePlural}MutationResponse"
        val updateResponse = "Update${pascalCasePlural}MutationResponse"
    }

    val rootTypeFieldNames by lazy { RootTypeFieldNames() }

    inner class RootTypeFieldNames {
        val create = "create${pascalCasePlural}"
        val read = plural
        val update = "update${pascalCasePlural}"
        val delete = "delete${pascalCasePlural}"
        val aggregate = "${plural}Aggregate"
    }

    fun allLabels(queryContext: QueryContext?): List<String> = listOf(mainLabel) + additionalLabels(queryContext)

    fun additionalLabels(queryContext: QueryContext?): List<String> =
        additionalLabels.map { mapLabelWithContext(it, queryContext) }

    private fun mapLabelWithContext(label: String, context: QueryContext?): String {
        return context?.resolve(label) ?: label
    }

    override fun toString(): String {
        return "Node('$name')"
    }

    fun asCypherNode(queryContext: QueryContext?, name: ChainString) = asCypherNode(queryContext, name.resolveName())
    fun asCypherNode(queryContext: QueryContext?, name: String? = null) =
        Cypher.node(mainLabel, additionalLabels(queryContext)).let {
            when {
                name != null -> it.named(name)
                else -> it
            }
        }

}

