package org.neo4j.graphql.asciidoc.ast

class ThematicBreak: StructuralNode(null) {

    override fun buildContent(contentExtractor: (CodeBlock) -> String): String {
        return "\n'''\n"
    }
}
