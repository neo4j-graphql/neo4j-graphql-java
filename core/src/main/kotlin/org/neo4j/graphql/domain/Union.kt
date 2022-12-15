package org.neo4j.graphql.domain

class Union(
    val name: String,
    val nodes: Map<String, Node>
) : NodeResolver {

    override fun getRequiredNode(name: String) = nodes.get(name)
        ?: throw IllegalArgumentException("unknown implementation $name for union ${this.name}")

    override fun getNode(name: String) = nodes[name]

}
