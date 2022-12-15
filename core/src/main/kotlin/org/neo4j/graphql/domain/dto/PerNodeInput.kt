package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.NodeResolver

open class PerNodeInput<T>(data: Map<Node, T>) : Map<Node, T> by data {

    companion object {

        @JvmStatic
        fun <T> create(
            nodeResolver: NodeResolver,
            data: Any?,
            convertValue: (node: Node, value: Any?) -> T?
        ): PerNodeInput<T>? = create(::PerNodeInput, nodeResolver, data, convertValue)

        @JvmStatic
        fun <R : PerNodeInput<T>, T> create(
            factory: (Map<Node, T>) -> R,
            nodeResolver: NodeResolver,
            data: Any?,
            convertValue: (node: Node, value: Any?) -> T?,
        ): R? {
            val map = data as? Map<*, *> ?: return null
            return map
                .mapNotNull { (key, value) ->
                    val name = key as? String ?: throw IllegalArgumentException("expected key to be string")
                    val node = nodeResolver.getRequiredNode(name)
                    val inputs = convertValue(node, value) ?: return@mapNotNull null
                    node to inputs
                }
                .takeIf { it.isNotEmpty() }
                ?.let { factory(it.toMap()) }
        }
    }
}
