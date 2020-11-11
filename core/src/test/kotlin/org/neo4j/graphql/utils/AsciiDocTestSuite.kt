package org.neo4j.graphql.utils

import com.intellij.rt.execution.junit.FileComparisonFailure
import org.codehaus.jackson.map.ObjectMapper
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import java.io.File
import java.net.URI
import java.util.*
import java.util.regex.Pattern
import java.util.stream.Stream
import javax.ws.rs.core.UriBuilder

/**
 * @param fileName the name of the test file
 * @param testCaseMarkers the markers for the test case
 * @param globalMarkers the markers for global blocks
 */
open class AsciiDocTestSuite(
        private val fileName: String,
        private val testCaseMarkers: List<String> = emptyList(),
        private val globalMarkers: List<String> = listOf(SCHEMA_MARKER)) {

    private val srcLocation = File("src/test/resources/", fileName).toURI()

    private val fileContent: StringBuilder = StringBuilder()

    /**
     * all parsed blocks of the test file
     */
    private val knownBlocks: MutableList<ParsedBlock> = mutableListOf()

    fun generateTests(): Stream<DynamicNode> = FileParser().parse()

    class ParsedBlock(
            val marker: String,
            val uri: URI,
            var headline: String? = null
    ) {
        var start: Int? = null
        var end: Int? = null
        var adjustedCode: String? = null
        val code: StringBuilder = StringBuilder()

        fun code() = code.trim().toString()
    }

    private inner class FileParser {

        private var root: DocumentLevel? = null
        private var currentDocumentLevel: DocumentLevel? = null
        private var currentDepth = 0

        private val globalCodeBlocks = mutableMapOf<String, ParsedBlock>()
        private var codeBlocksOfTest = mutableMapOf<String, ParsedBlock>()

        fun parse(): Stream<DynamicNode> {
            val file = File(AsciiDocTestSuite::class.java.getResource("/$fileName").toURI())
            val lines = file.readLines()
            val terminatorElement = testCaseMarkers.lastOrNull()

            var title: String? = null
            var currentBlock: ParsedBlock? = null
            var globalDone = false

            var ignore = false
            var inside = false
            var offset = 0

            loop@ for ((lineNr, line) in lines.withIndex()) {
                fileContent.append(line).append('\n')
                if (line.startsWith("#") || line.startsWith("//")) {
                    offset += line.length + 1
                    continue
                }
                val headlineMatcher = HEADLINE_PATTERN.matcher(line)

                when {
                    !globalDone && globalMarkers.contains(line) -> currentBlock = startBlock(line, lineNr, globalCodeBlocks)
                    testCaseMarkers.contains(line) -> {
                        globalDone = true
                        currentBlock = startBlock(line, lineNr, codeBlocksOfTest)
                    }
                    line == "----" -> {
                        inside = !inside
                        if (inside) {

                            currentBlock?.start = offset + line.length + 1

                        } else if (currentBlock != null) {

                            currentBlock.end = offset
                            when (currentBlock.marker) {

                                SCHEMA_MARKER -> {
                                    val schemaTests = schemaTestFactory(currentBlock.code())
                                    currentDocumentLevel?.tests?.add(schemaTests)
                                    if (terminatorElement == null) {
                                        break@loop
                                    }
                                }

                                terminatorElement -> {
                                    createTests(title, lineNr, ignore)
                                    currentBlock = null
                                    ignore = false
                                }
                            }

                        }
                    }

                    headlineMatcher.matches() -> {
                        val depth = headlineMatcher.group(1).length
                        title = headlineMatcher.group(2)
                        val uri = UriBuilder.fromUri(srcLocation).queryParam("line", lineNr + 1).build()
                        handleHeadline(title, uri, depth)
                    }

                    line.startsWith("CAUTION:") -> ignore = true

                    inside -> currentBlock?.code?.append(line)?.append("\n")

                }
                offset += line.length + 1 // +1 b/c of newline
            }

            if (GENERATE_TEST_FILE_DIFF) {
                // this test prints out the adjusted test file
                root?.afterTests?.add(DynamicTest.dynamicTest("Adjusted Tests", srcLocation, this@AsciiDocTestSuite::printAdjustedTestFile))
            }
            return root?.generateTests() ?: Stream.empty()
        }

        private fun createTests(title: String?, lineNr: Int, ignore: Boolean) {
            val tests = testFactory(
                    title ?: throw IllegalStateException("Title should be defined (line $lineNr)"),
                    globalCodeBlocks,
                    codeBlocksOfTest,
                    ignore)
            currentDocumentLevel?.tests?.add(tests)
            codeBlocksOfTest = mutableMapOf()
        }

        private fun handleHeadline(title: String, uri: URI, depth: Int) {
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

    }

    private fun printAdjustedTestFile() {
        knownBlocks.sortWith(compareByDescending<ParsedBlock> { it.start }
            .thenByDescending { testCaseMarkers.indexOf(it.marker) })
        val rebuildTest = StringBuffer(fileContent)
        knownBlocks.filter { it.adjustedCode != null }.forEach { block ->
            val start = block.start ?: throw IllegalArgumentException("unknown start position")
            if (block.end == null) {
                rebuildTest.insert(start, ".${block.headline}\n${block.marker}\n----\n${block.adjustedCode}\n----\n\n")
            } else {
                rebuildTest.replace(start, block.end!!, block.adjustedCode + "\n")
            }
        }
        if (!Objects.equals(rebuildTest.toString(), fileContent.toString())) {
            // This special exception will be handled by intellij so that you can diff directly with the file
            throw FileComparisonFailure(null, rebuildTest.toString(), fileContent.toString(),
                    null, File("src/test/resources/", fileName).absolutePath)
        }
    }

    fun startBlock(marker: String, lineIndex: Int, blocks: MutableMap<String, ParsedBlock>): ParsedBlock {
        val uri = UriBuilder.fromUri(srcLocation).queryParam("line", lineIndex + 1).build()
        val block = ParsedBlock(marker, uri)
        knownBlocks += block
        blocks[marker] = block
        return block
    }

    protected open fun testFactory(title: String, globalBlocks: Map<String, ParsedBlock>, codeBlocks: Map<String, ParsedBlock>, ignore: Boolean): List<DynamicNode> {
        return emptyList()
    }

    protected open fun schemaTestFactory(schema: String): List<DynamicNode> {
        return emptyList()
    }

    protected fun getOrCreateBlock(codeBlocks: Map<String, ParsedBlock>, marker: String, headline: String): ParsedBlock? {
        var block = codeBlocks[marker]
        if (block == null && GENERATE_TEST_FILE_DIFF) {
            val insertPoints = testCaseMarkers.indexOf(marker).let { testCaseMarkers.subList(0, it).asReversed() }
            val insertPoint = insertPoints.mapNotNull { codeBlocks[it] }.firstOrNull()
                    ?: throw IllegalArgumentException("none of the insert points $insertPoints found")
            block = ParsedBlock(marker, insertPoint.uri, headline)
            block.start = (insertPoint.end ?: throw IllegalStateException("no start for block defined")) + 6
            knownBlocks += block
        }
        return block
    }

    companion object {
        val GENERATE_TEST_FILE_DIFF = System.getProperty("neo4j-graphql-java.generate-test-file-diff", "true") == "true"
        val MAPPER = ObjectMapper()
        val HEADLINE_PATTERN: Pattern = Pattern.compile("^(=+) (.*)$")

        const val SCHEMA_MARKER = "[source,graphql,schema=true]"
        const val SCHEMA_CONFIG_MARKER = "[source,json,schema-config=true]"

        class DocumentLevel(
                val parent: DocumentLevel?,
                val name: String,
                private val testSourceUri: URI
        ) {
            private val children = mutableListOf<DocumentLevel>()
            val tests = mutableListOf<List<DynamicNode>>()
            val afterTests = mutableListOf<DynamicNode>()

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
                afterTests.forEach { streamBuilder.add(it) }
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
