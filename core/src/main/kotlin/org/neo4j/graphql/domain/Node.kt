package org.neo4j.graphql.domain

import graphql.language.Comment
import graphql.language.Description
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.decapitalize
import org.neo4j.graphql.domain.directives.NodeAnnotations
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.naming.NodeNames
import org.neo4j.graphql.handler.utils.ChainString

class Node(
    name: String,
    description: Description? = null,
    comments: List<Comment> = emptyList(),
    fields: List<BaseField>,
    interfaces: List<Interface>,
    override val annotations: NodeAnnotations,
    schema: Schema,
) : ImplementingType(name, description, comments, fields, interfaces, annotations, schema) {

    override val namings = NodeNames(name, annotations)
    val hasRelayId: Boolean get() = fields.any { it.annotations.relayId != null }

    val mainLabel: String get() = annotations.node?.labels?.firstOrNull() ?: name

    val additionalLabels: List<String> get() = annotations.node?.labels?.drop(1) ?: emptyList()
    fun allLabels(queryContext: QueryContext?): List<String> = listOf(mainLabel) + additionalLabels(queryContext)

    fun additionalLabels(queryContext: QueryContext?): List<String> =
        additionalLabels.map { mapLabelWithContext(it, queryContext) }

    private fun mapLabelWithContext(label: String, context: QueryContext?): String {
        return context?.resolve(label) ?: label
    }

    override fun toString(): String {
        return "Node('$name')"
    }

    fun asCypherNodeWithNodeName(queryContext: QueryContext?) = asCypherNode(queryContext, name.decapitalize())

    fun asCypherNode(queryContext: QueryContext?, name: ChainString) = asCypherNode(queryContext, name.resolveName())
    fun asCypherNode(queryContext: QueryContext?, name: String? = null) =
        Cypher.node(mainLabel, additionalLabels(queryContext)).let {
            when {
                name != null -> it.named(name)
                else -> it
            }
        }

}

