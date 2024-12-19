package org.neo4j.graphql.asciidoc.ast

import org.neo4j.graphql.domain.CodeBlockPredicate
import java.net.URI

open class Section(
    val title: String,
    val uri: URI,
    override val parent: Section?,
) : StructuralNode(parent) {

    override fun toString(): String {
        return "Section(title='$title')"
    }

    override fun buildContent(contentExtractor: (CodeBlock) -> String): String {

        val builder = StringBuilder("\n")
        var current: Section? = this
        do {
            builder.append("=")
            current = current?.parent
            if (current is Document && current.blocks.filterIsInstance<Section>().size > 1) {
                // we are in a document with multiple sections, so we need to add another level to the title
                builder.append("=")
            }
        } while (current != null && current !is Document)
        builder.append(" ${title}\n")
        blocks.forEach {
            builder.append(it.buildContent(contentExtractor))
        }
        return builder.toString()
    }

    /**
     * Find all directly nested code blocks of a given section matching the language and filter
     */
    fun findCodeBlocks(
        predicate: CodeBlockPredicate,
    ): List<CodeBlock> =
        blocks
            .filterIsInstance<CodeBlock>()
            .filter { predicate.matches(it) }

    /**
     * Find a single code block of a given section matching the language and filter
     */
    fun findSingleOrNullCodeBlock(
        predicate: CodeBlockPredicate,
    ): CodeBlock? =
        findCodeBlocks(predicate)
            .also { require(it.size <= 1) { "Found more than one code block matching the predicate" } }
            .singleOrNull()

    /**
     * Find all setup blocks for a given section, including the setup blocks of the parent sections
     */
    fun findSetupCodeBlocks(
        predicate: CodeBlockPredicate,
    ): List<CodeBlock> {
        val result = mutableListOf<CodeBlock>()
        var currentSection: Section? = this
        while (currentSection != null) {
            result.addAll(currentSection.findCodeBlocks(predicate))
            currentSection.blocks
                .filterIsInstance<Section>()
                .filter { it.title == "Setup" }
                .forEach { result.addAll(it.findCodeBlocks(predicate)) }
            currentSection = currentSection.parent
        }
        return result
    }

    fun addAfter(insertPoint: StructuralNode?, node: StructuralNode) {
        if (insertPoint == null) {
            blocks.add(node)
        } else {
            val index = blocks.indexOf(insertPoint)
            if (index == -1) {
                blocks.add(node)
            } else {
                blocks.add(index + 1, node)
            }
        }
    }
}
