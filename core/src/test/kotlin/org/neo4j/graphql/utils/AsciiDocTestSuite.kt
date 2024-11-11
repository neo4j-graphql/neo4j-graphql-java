package org.neo4j.graphql.utils

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.intellij.rt.execution.junit.FileComparisonFailure
import demo.org.neo4j.graphql.utils.asciidoc.AsciiDocParser
import demo.org.neo4j.graphql.utils.asciidoc.ast.*
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import java.io.File
import java.io.FileWriter
import java.util.*
import java.util.stream.Stream
import kotlin.reflect.KMutableProperty1

/**
 * @param fileName the name of the test file
 * @param relevantBlocks a list of pairs of filter functions and properties to set the found code blocks
 */
abstract class AsciiDocTestSuite<T>(
    private val fileName: String,
    private val relevantBlocks: List<CodeBlockMatcher<T>>,
) {

    abstract class CodeBlockMatcher<T>(
        val language: String,
        val filter: Map<String, String?> = emptyMap(),
        val exactly: Boolean = false
    ) {
        abstract fun set(testData: T, codeBlock: CodeBlock)
    }


    private val srcLocation = File("src/test/resources/", fileName).toURI()

    private val document = AsciiDocParser(fileName).parse()

    /**
     * all parsed code blocks of the test file
     */
    private val knownBlocks = collectBlocks(document).toMutableList()

    fun generateTests(): Stream<DynamicNode> {
        val tests = createTestsOfSection(document).toMutableList()

        if (UPDATE_TEST_FILE) {
            // this test prints out the adjusted test file
            tests += DynamicTest.dynamicTest(
                "Write updated Testfile",
                srcLocation,
                this@AsciiDocTestSuite::writeAdjustedTestFile
            )
        } else if (REFORMAT_TEST_FILE) {
            tests += DynamicTest.dynamicTest("Reformat Testfile", srcLocation, this@AsciiDocTestSuite::reformatTestFile)
        } else if (GENERATE_TEST_FILE_DIFF) {
            // this test prints out the adjusted test file
            tests += DynamicTest.dynamicTest(
                "Adjusted Tests",
                srcLocation,
                this@AsciiDocTestSuite::printAdjustedTestFile
            )
        }

        return if (FLATTEN_TESTS) flatten(tests.stream(), "$fileName:") else tests.stream()
    }

    private fun createTestsOfSection(section: Section, parentIgnoreReason: String? = null): List<DynamicNode> {

        val tests = mutableListOf<DynamicNode>()
        var testCase = createTestCase(section)
        var ignoreReason: String? = null
        for (node in section.blocks) {
            when (node) {
                is CodeBlock -> {
                    for (matcher in relevantBlocks) {
                        if (testCase != null && node.matches(matcher.language, matcher.filter, matcher.exactly)) {
                            matcher.set(testCase, node)
                        }
                    }


                }

                is Block -> {
                    val blockContent = node.content.trim()
                    if (blockContent.startsWith("CAUTION:")) {
                        ignoreReason = blockContent.substring("CAUTION:".length).trim()
                    }
                }

                is ThematicBreak -> {
                    if (testCase != null) {
                        tests += createTests(testCase, section, ignoreReason ?: parentIgnoreReason)
                    }
                    ignoreReason = null
                    testCase = createTestCase(section) ?: continue
                }

                is Section -> {
                    val nestedTests = createTestsOfSection(node, ignoreReason ?: parentIgnoreReason)
                    if (nestedTests.isNotEmpty()) {
                        tests += DynamicContainer.dynamicContainer(node.title, node.uri, nestedTests.stream())
                    }
                }
            }
        }
        if (testCase != null) {
            tests += createTests(testCase, section, ignoreReason ?: parentIgnoreReason)
        }
        return tests
    }

    abstract fun createTestCase(section: Section): T?

    abstract fun createTests(testCase: T, section: Section, ignoreReason: String?): List<DynamicNode>

    private fun flatten(stream: Stream<out DynamicNode>, name: String): Stream<DynamicNode> {
        return stream.flatMap {
            when (it) {
                is DynamicContainer -> flatten(it.children, "$name[${it.displayName}]")
                is DynamicTest -> Stream.of(DynamicTest.dynamicTest("$name[${it.displayName}]", it.executable))
                else -> throw IllegalArgumentException("unknown type ${it.javaClass.name}")
            }
        }
    }

    private fun collectBlocks(node: StructuralNode): List<CodeBlock> {
        return when (node) {
            is CodeBlock -> listOf(node)
            else -> node.blocks.flatMap { collectBlocks(it) }
        }
    }

    private fun writeAdjustedTestFile() {
        val content = generateAdjustedFileContent({ block ->
            block.generatedContent.takeIf {
                when {
                    UPDATE_SEMANTIC_EQUALLY_BLOCKS -> block.semanticEqual && (block.tandemUpdate?.semanticEqual ?: true)
                    else -> true
                }
            }
        })
        FileWriter(File("src/test/resources/", fileName)).use {
            it.write(content)
        }
    }

    private fun reformatTestFile() {
        val content = generateAdjustedFileContent { it.reformattedContent }
        FileWriter(File("src/test/resources/", fileName)).use {
            it.write(content)
        }
    }

    private fun printAdjustedTestFile() {
        val rebuildTest = generateAdjustedFileContent()
        if (!Objects.equals(rebuildTest, document.content)) {
            // This special exception will be handled by intellij so that you can diff directly with the file
            throw FileComparisonFailure(
                null, document.content, rebuildTest,
                File("src/test/resources/", fileName).absolutePath, null
            )
        }
    }

    private fun generateAdjustedFileContent(extractor: (CodeBlock) -> String? = { it.generatedContent }): String {
        knownBlocks.sortWith(compareByDescending { it.start })
        val rebuildTest = StringBuffer(document.content)
        knownBlocks.filter { extractor(it) != null }
            .forEach { block ->
                val start = block.start ?: error("unknown start position")
                if (block.end == null) {
                    rebuildTest.insert(
                        start,
                        ".${block.caption}\n${block.marker}\n----\n${extractor(block)}\n----\n\n"
                    )
                } else {
                    rebuildTest.replace(start, block.end!!, extractor(block) + "\n")
                }
            }
        return rebuildTest.toString()
    }

    fun createCodeBlock(
        insertPoint: CodeBlock,
        language: String,
        headline: String,
        attributes: Map<String, String?> = emptyMap()
    ): CodeBlock? {
        if (!GENERATE_TEST_FILE_DIFF && !UPDATE_TEST_FILE) {
            return null
        }
        val codeBlock = CodeBlock(insertPoint.uri, language, insertPoint.parent, attributes)
            .apply {
                caption = headline
                content = ""
            }
        codeBlock.start = (insertPoint.end ?: error("no start for block defined")) + 6
        knownBlocks += codeBlock
        return codeBlock
    }

    companion object {
        /**
         * to find broken tests easy by its console output, enable this feature
         */
        val FLATTEN_TESTS = System.getProperty("neo4j-graphql-java.flatten-tests", "false") == "true"
        val GENERATE_TEST_FILE_DIFF = System.getProperty("neo4j-graphql-java.generate-test-file-diff", "true") == "true"
        val REFORMAT_TEST_FILE = System.getProperty("neo4j-graphql-java.reformat", "false") == "true"
        val UPDATE_TEST_FILE = System.getProperty("neo4j-graphql-java.update-test-file", "false") == "true"
        val UPDATE_SEMANTIC_EQUALLY_BLOCKS =
            System.getProperty("neo4j-graphql-java.update-semantic-equally-blocks", "false") == "true"
        val MAPPER = ObjectMapper().registerKotlinModule()

        fun String.parseJsonMap(): Map<String, Any?> = this.let {
            @Suppress("UNCHECKED_CAST")
            MAPPER.readValue(this, Map::class.java) as Map<String, Any?>
        }


        /**
         * Find all directly nested code blocks of a given section matching the language and filter
         */
        private fun findCodeBlocks(
            section: Section,
            language: String,
            filter: Map<String, String?> = emptyMap()
        ): List<CodeBlock> =
            section.blocks
                .filterIsInstance<CodeBlock>()
                .filter { it.matches(language, filter) }

        /**
         * Find all setup blocks for a given section, including the setup blocks of the parent sections
         */
        fun findSetupCodeBlocks(
            section: Section,
            language: String,
            fiter: Map<String, String?> = emptyMap()
        ): List<CodeBlock> {
            val result = mutableListOf<CodeBlock>()
            var currentSection: Section? = section
            while (currentSection != null) {
                result.addAll(findCodeBlocks(currentSection, language, fiter))
                currentSection.blocks
                    .filterIsInstance<Section>()
                    .filter { it.title == "Setup" }
                    .forEach { result.addAll(findCodeBlocks(it, language, fiter)) }
                currentSection = currentSection.parent
            }
            return result
        }

        fun <T> matcher(
            language: String,
            filter: Map<String, String?> = emptyMap(),
            exactly: Boolean = false,
            setter: KMutableProperty1<T, CodeBlock?>
        ): CodeBlockMatcher<T> =
            matcher(language, filter, exactly) { testData, codeBlock -> setter.set(testData, codeBlock) }

        fun <T> matcher(
            language: String,
            filter: Map<String, String?> = emptyMap(),
            exactly: Boolean = false,
            setter: (T, CodeBlock) -> Unit
        ): CodeBlockMatcher<T> =
            object : CodeBlockMatcher<T>(language, filter, exactly) {
                override fun set(testData: T, codeBlock: CodeBlock) {
                    setter(testData, codeBlock)
                }
            }

    }

}
