package demo.org.neo4j.graphql.utils.asciidoc.ast

import java.net.URI

class CodeBlock(
    val uri: URI,
    val language: String,
    override val parent: Section,
    val attributes: Map<String, String?>
) : StructuralNode(parent) {

    var caption: String? = null

    var start: Int? = null
    var end: Int? = null

    lateinit var content: String

    /**
     * The content that was generated but diffs to the current content
     */
    var generatedContent: String? = null

    /**
     * The original content reformatted
     */
    var reformattedContent: String? = null

    val marker: String
        get() = "[source,$language${attributes.map { ",${it.key}${it.value?.let { "=${it}" } ?: ""}" }.joinToString()}]"

    override fun toString(): String {
        return "CodeBlock(language='$language', attributes=$attributes)"
    }

    fun matches(language: String, filter: Map<String, String?> = emptyMap(), exactly: Boolean = false) =
        this.language == language && filter.all { (k, v) -> attributes[k] == v } && (!exactly || attributes.size == filter.size)


    companion object {

        fun parseMeta(parent: Section, uri: URI, meta: String): CodeBlock {
            if (!meta.startsWith("[source,")) {
                error("Invalid code block meta: $meta")
            }

            val parts = meta.substring(8, meta.indexOf("]")).trim().split(",")
            val language = parts[0]
            val attributes = parts.slice(1..<parts.size).map {
                val attributeParts = it.split("=")
                attributeParts[0] to attributeParts.getOrNull(1)
            }.toMap()

            return CodeBlock(uri, language, parent, attributes)
        }

    }

}
