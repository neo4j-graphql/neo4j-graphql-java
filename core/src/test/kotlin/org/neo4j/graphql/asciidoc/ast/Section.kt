package org.neo4j.graphql.asciidoc.ast

import java.net.URI

open class Section(
    val title: String,
    val uri: URI,
    override val parent: Section?,
) : StructuralNode(parent) {

    override fun toString(): String {
        return "Section(title='$title')"
    }
}
