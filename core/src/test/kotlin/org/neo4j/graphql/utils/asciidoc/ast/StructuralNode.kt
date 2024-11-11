package demo.org.neo4j.graphql.utils.asciidoc.ast

sealed class StructuralNode(
    open val parent: StructuralNode?
) {
    val blocks = mutableListOf<StructuralNode>()
}
