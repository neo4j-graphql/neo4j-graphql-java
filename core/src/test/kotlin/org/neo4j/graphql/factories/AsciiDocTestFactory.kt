package org.neo4j.graphql.factories

import com.intellij.rt.execution.junit.FileComparisonFailure
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.neo4j.graphql.asciidoc.AsciiDocParser
import org.neo4j.graphql.asciidoc.ast.*
import org.neo4j.graphql.domain.TestCase
import org.neo4j.graphql.domain.TestCase.Setup
import org.neo4j.graphql.utils.TestUtils
import java.nio.file.Path
import java.util.*
import java.util.stream.Stream
import kotlin.io.path.writeText

/**
 * @param file the test file
 */
abstract class AsciiDocTestFactory(
    protected val file: Path,
    private val createMissingBlocks: Boolean = true
) {

    private val document = AsciiDocParser(file).parse()

    fun generateTests(): Stream<DynamicNode> {
        val tests = createTestsOfSection(document).toMutableList()

        if (UPDATE_TEST_FILE) {
            // this test prints out the adjusted test file
            tests += DynamicTest.dynamicTest(
                "Write updated Testfile",
                file.toUri(),
                this@AsciiDocTestFactory::writeAdjustedTestFile
            )
        } else if (GENERATE_TEST_FILE_DIFF) {
            // this test prints out the adjusted test file
            tests += DynamicTest.dynamicTest(
                "Adjusted Tests",
                file.toUri(),
                this@AsciiDocTestFactory::printAdjustedTestFile
            )
        }

        return if (FLATTEN_TESTS) {
            val relativeFile = Path.of(TestUtils.TEST_RESOURCES).relativize(file)
            flatten(tests.stream(), "$relativeFile:")
        } else {
            tests.stream()
        }
    }

    private fun createTestsOfSection(section: Section, parentIgnoreReason: String? = null): List<DynamicNode> {

        val tests = mutableListOf<DynamicNode>()
        val setup = Setup(section)
        var testCase = TestCase(setup)
        var ignoreReason: String? = null
        for (node in section.blocks) {
            when (node) {
                is CodeBlock -> testCase.parseCodeBlock(node)

                is Table -> testCase.parseTable(node)

                is Block -> {
                    val blockContent = node.content.trim()
                    if (blockContent.startsWith("CAUTION:")) {
                        ignoreReason = blockContent.substring("CAUTION:".length).trim()
                    }
                }

                is ThematicBreak -> {
                    tests += createTests(testCase, section, ignoreReason ?: parentIgnoreReason)
                    ignoreReason = null
                    testCase = TestCase(setup)
                }

                is Section -> {
                    val nestedTests = createTestsOfSection(node, ignoreReason ?: parentIgnoreReason)
                    if (nestedTests.isNotEmpty()) {
                        tests += DynamicContainer.dynamicContainer(node.title, node.uri, nestedTests.stream())
                    }
                }
            }
        }
        tests += createTests(testCase, section, ignoreReason ?: parentIgnoreReason)
        return tests
    }


    abstract fun createTests(testCase: TestCase, section: Section, ignoreReason: String?): List<DynamicNode>

    private fun flatten(stream: Stream<out DynamicNode>, name: String): Stream<DynamicNode> {
        return stream.flatMap {
            when (it) {
                is DynamicContainer -> flatten(it.children, "$name[${it.displayName}]")
                is DynamicTest -> Stream.of(DynamicTest.dynamicTest("$name[${it.displayName}]", it.executable))
                else -> throw IllegalArgumentException("unknown type ${it.javaClass.name}")
            }
        }
    }

    private fun writeAdjustedTestFile() {
        val content = generateAdjustedFileContent {
            if (UPDATE_SEMANTIC_EQUALLY_BLOCKS && it.semanticEqual && (it.tandemUpdate?.semanticEqual != false))
                it.generatedContent
            else
                it.content
            it.generatedContent ?: it.content
        }

        file.writeText(content)
    }

    private fun printAdjustedTestFile() {
        val rebuildTest = generateAdjustedFileContent()
        if (!Objects.equals(rebuildTest, document.content)) {
            // This special exception will be handled by intellij so that you can diff directly with the file
            throw FileComparisonFailure(
                null, document.content, rebuildTest,
                file.toFile().absolutePath, null
            )
        }
    }

    protected fun generateAdjustedFileContent(
        extractor: (CodeBlock) -> String = { it.generatedContent ?: it.content },
    ): String = document.buildContent(extractor)

    fun createCodeBlock(
        insertPoint: CodeBlock,
        language: String,
        headline: String,
        attributes: Map<String, String?> = emptyMap()
    ): CodeBlock? {
        if (!createMissingBlocks || (!GENERATE_TEST_FILE_DIFF && !UPDATE_TEST_FILE)) {
            return null
        }
        val codeBlock = CodeBlock(insertPoint.uri, language, insertPoint.parent, attributes)
            .apply {
                caption = headline
                content = ""
            }
        insertPoint.parent.addAfter(insertPoint, codeBlock)
        return codeBlock
    }

    companion object {
        /**
         * to find broken tests easy by its console output, enable this feature
         */
        val FLATTEN_TESTS = System.getProperty("neo4j-graphql-java.flatten-tests", "false") == "true"
        val GENERATE_TEST_FILE_DIFF =
            System.getProperty("neo4j-graphql-java.generate-test-file-diff", "false") == "true"
        val UPDATE_TEST_FILE = System.getProperty("neo4j-graphql-java.update-test-file", "false") == "true"
        val UPDATE_SEMANTIC_EQUALLY_BLOCKS =
            System.getProperty("neo4j-graphql-java.update-semantic-equally-blocks", "false") == "true"
    }

}
