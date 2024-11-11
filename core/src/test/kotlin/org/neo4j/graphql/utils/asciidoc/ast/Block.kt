package demo.org.neo4j.graphql.utils.asciidoc.ast

class Block(
    parent: StructuralNode,
    val content: String
) : StructuralNode(parent) {

    override fun toString(): String {
        return "Block(content='$content')"
    }
}
