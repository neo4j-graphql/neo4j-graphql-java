package org.neo4j.graphql.domain

import org.neo4j.graphql.domain.directives.Annotations
import org.neo4j.graphql.domain.naming.UnionEntityOperations

class Union(
    override val name: String,
    val nodes: Map<String, Node>,
    override val annotations: Annotations,
) : NodeResolver, Entity {

    override fun getRequiredNode(name: String) = nodes[name]
        ?: throw IllegalArgumentException("unknown implementation $name for union ${this.name}")

    override fun getNode(name: String) = nodes[name]
    override val operations = UnionEntityOperations(name, annotations)
}
