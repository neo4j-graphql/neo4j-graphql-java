package org.neo4j.graphql.utils

import org.codehaus.jackson.map.ObjectMapper
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import java.io.File
import java.net.URI
import java.util.regex.Pattern
import java.util.stream.Stream
import javax.ws.rs.core.UriBuilder

open class AsciiDocTestSuite(private val fileName: String) {

    class ParsedBlock(
            val uri: URI,
            val code: StringBuilder
    )

    fun parse(blocks: LinkedHashSet<String>): Stream<DynamicNode> {
        val file = File(AsciiDocTestSuite::class.java.getResource("/$fileName").toURI())
        val srcLocation = File("src/test/resources/", fileName).toURI()
        var root: DocumentLevel? = null
        var currentDocumentLevel: DocumentLevel? = null

        val lines = file.readLines()
        val terminatorElement = blocks.lastOrNull()

        var schema: String? = null
        var title: String? = null
        var current: StringBuilder? = null

        var codeBlocks = mutableMapOf<String, ParsedBlock>()
        var ignore = false
        var inside = false

        var currentDepth = 0

        loop@ for ((lineNr, line) in lines.withIndex()) {
            if (line.startsWith("#") || line.startsWith("//")) {
                continue
            }
            val headlineMatcher = HEADLINE_PATTERN.matcher(line)
            when {
                line == "[source,graphql,schema=true]" -> schema = ""
                blocks.contains(line) -> {
                    current = StringBuilder()
                    codeBlocks[line] = ParsedBlock(
                            UriBuilder.fromUri(srcLocation).queryParam("line", lineNr + 1).build(),
                            current
                    )
                }
                line == "----" -> {
                    if (schema?.isNotBlank() == true && current == null) {
                        val tests = schemaTestFactory(schema)
                        currentDocumentLevel?.tests?.add(tests)
                        if (terminatorElement == null) {
                            break@loop
                        }
                    }
                    if (codeBlocks[terminatorElement]?.code?.isNotEmpty() == true) {
                        val tests = testFactory(
                                title ?: throw IllegalStateException("Title should be defined (line $lineNr)"),
                                schema ?: throw IllegalStateException("Schema should be defined"),
                                codeBlocks,
                                ignore)
                        currentDocumentLevel?.tests?.add(tests)
                        codeBlocks = mutableMapOf()
                        ignore = false
                    }
                    inside = !inside
                }
                headlineMatcher.matches() -> {
                    val uri = UriBuilder.fromUri(srcLocation).queryParam("line", lineNr + 1).build()
                    val depth = headlineMatcher.group(1).length
                    title = headlineMatcher.group(2)
                    if (root == null) {
                        root = DocumentLevel(null, title, uri)
                        currentDocumentLevel = root
                    } else {
                        val parent = when {
                            depth > currentDepth -> currentDocumentLevel
                            depth == currentDepth -> currentDocumentLevel?.parent
                                    ?: throw IllegalStateException("cannot create sub-level on null")
                            else -> currentDocumentLevel?.parent?.parent
                                    ?: throw IllegalStateException("cannot create sub-level on null")
                        }
                        currentDocumentLevel = DocumentLevel(parent, title, uri)
                    }
                    currentDepth = depth
                }
                line.startsWith("CAUTION:") -> ignore = true
                inside -> when {
                    current != null -> current.append(line).append("\n")
                    schema != null -> schema += line + "\n"
                }
            }
        }

        return root?.generateTests() ?: Stream.empty()
    }

    open fun testFactory(title: String, schema: String, codeBlocks: Map<String, ParsedBlock>, ignore: Boolean): List<DynamicNode> {
        return emptyList()
    }

    open fun schemaTestFactory(schema: String): List<DynamicNode> {
        return emptyList()
    }

    companion object {
        val MAPPER = ObjectMapper()
        val HEADLINE_PATTERN: Pattern = Pattern.compile("^(=+) (.*)$")

        class DocumentLevel(
                val parent: DocumentLevel?,
                val name: String,
                private val testSourceUri: URI
        ) {
            private val children = mutableListOf<DocumentLevel>()
            val tests = mutableListOf<List<DynamicNode>>()

            init {
                parent?.children?.add(this)
            }

            fun generateTests(): Stream<DynamicNode> {
                val streamBuilder = Stream.builder<DynamicNode>()
                if (tests.size > 1) {
                    if (children.isNotEmpty()) {
                        streamBuilder.add(DynamicContainer.dynamicContainer(name, testSourceUri, children.stream().flatMap { it.generateTests() }))
                    }
                    for ((index, test) in tests.withIndex()) {
                        streamBuilder.add(DynamicContainer.dynamicContainer(name + " " + (index + 1), testSourceUri, test.stream()))
                    }
                } else {
                    val nodes = Stream.concat(
                            tests.stream().flatMap { it.stream() },
                            children.stream().flatMap { it.generateTests() }
                    )
                    streamBuilder.add(DynamicContainer.dynamicContainer(name, testSourceUri, nodes))
                }
                return streamBuilder.build()
            }
        }

        private fun fixNumber(v: Any?): Any? = when (v) {
            is Float -> v.toDouble()
            is Int -> v.toLong()
            is Iterable<*> -> v.map { fixNumber(it) }
            is Sequence<*> -> v.map { fixNumber(it) }
            is Map<*, *> -> v.mapValues { fixNumber(it.value) }
            else -> v
        }

        fun fixNumbers(params: Map<String, Any?>) = params.mapValues { (_, v) -> fixNumber(v) }

        fun String.parseJsonMap(): Map<String, Any?> = this.let {
            @Suppress("UNCHECKED_CAST")
            MAPPER.readValue(this, Map::class.java) as Map<String, Any?>
        }

        fun String.normalize(): String = this
            .replace(Regex("\\s+"), " ")
            .replace(Regex(",(\\S)"), ", $1")
            .replace(Regex("\\{(\\S)"), "{ $1")
            .replace(Regex("(\\S)}"), "$1 }")
            .replace(Regex(":(\\S)"), ": $1")
    }

}
