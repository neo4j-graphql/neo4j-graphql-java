package org.neo4j.graphql.asciidoc.ast

sealed class StructuralNode(
    open val parent: StructuralNode?
) {
    val blocks = mutableListOf<StructuralNode>()

    abstract fun buildContent(contentExtractor: (CodeBlock) -> String): String
}
