package org.neo4j.graphql.asciidoc.ast

class Block(
    parent: StructuralNode,
    var content: String
) : StructuralNode(parent) {

    override fun toString(): String {
        return "Block(content='$content')"
    }

    override fun buildContent(contentExtractor: (CodeBlock) -> String): String {
        return content + "\n"
    }
}
