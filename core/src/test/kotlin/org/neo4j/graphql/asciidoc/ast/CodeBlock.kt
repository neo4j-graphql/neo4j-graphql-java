package org.neo4j.graphql.asciidoc.ast

import java.net.URI

class CodeBlock(
    val uri: URI,
    val language: String,
    override val parent: Section,
    val attributes: Map<String, String?>
) : StructuralNode(parent) {

    var caption: String? = null

    lateinit var content: String

    /**
     * The content that was generated but diffs to the current content
     */
    var generatedContent: String? = null

    /**
     * The original content reformatted
     */
    var reformattedContent: String? = null

    var semanticEqual = false

    /**
     * update only if other (tandem) is also updated
     */
    var tandemUpdate: CodeBlock? = null

    var adjustedAttributes: MutableMap<String, String?>? = null

    val adjustedMarker: String
        get() = "[source,$language${
            (adjustedAttributes ?: attributes).map { ",${it.key}${it.value?.let { "=${it}" } ?: ""}" }.joinToString("")
        }]"

    override fun toString(): String {
        return "CodeBlock(language='$language', attributes=$attributes${adjustedAttributes?.let { ", adjustedAttributes=$it" } ?: ""})"
    }

    fun matches(language: String, filter: Map<String, String?> = emptyMap(), exactly: Boolean = false) =
        this.language == language && filter.all { (k, v) -> attributes[k] == v } && (!exactly || attributes.size == filter.size)

    override fun buildContent(contentExtractor: (CodeBlock) -> String): String {
        val builder = StringBuilder()
        caption?.let {
            builder.append("\n.${it}\n")
        }
        builder.append(adjustedMarker)
        builder.append("\n----\n")
        builder.append(contentExtractor(this))
        builder.append("\n----\n")
        return builder.toString()
    }
}
