package org.neo4j.graphql.tools

import org.neo4j.graphql.asciidoc.AsciiDocParser
import org.neo4j.graphql.asciidoc.ast.CodeBlock
import org.neo4j.graphql.asciidoc.ast.Document
import org.neo4j.graphql.asciidoc.ast.StructuralNode
import org.neo4j.graphql.domain.CodeBlockPredicate
import org.neo4j.graphql.utils.CypherUtils
import org.neo4j.graphql.utils.JsonUtils
import org.neo4j.graphql.utils.JsonUtils.parseJson
import org.neo4j.graphql.utils.SchemaUtils
import org.neo4j.graphql.utils.TestUtils
import org.neo4j.graphql.utils.TestUtils.IS_TEST_FILE
import org.neo4j.graphql.utils.TestUtils.TEST_RESOURCES
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

open class AsciidocReformater {

    fun run() {
        listOf(
            "tck-test-files/schema/v2",
            "tck-test-files/cypher/v2",
            "integration-test-files"
        )
            .forEach {
                val root = Paths.get("$TEST_RESOURCES$it")
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                        handleFile(path)
                        return FileVisitResult.CONTINUE
                    }
                })
            }
    }

    open fun handleFile(file: Path) {
        if (!IS_TEST_FILE.test(file)) {
            return
        }

        val targetDocument = AsciiDocParser(file).parse()

        val blocks = collectCodeBlocks(targetDocument)
        for (block in blocks) {
            reformatBlock(block)
        }
        writeDocument(targetDocument, file)
    }

    fun reformatBlock(block: CodeBlock) {
        if (block.reformattedContent != null) {
            // already reformatted
            return
        }
        try {
            when {
                // pretty print the schema
                CodeBlockPredicate.GRAPHQL_AUGMENTED_SCHEMA.matches(block) ->
                    block.reformattedContent = SchemaUtils.prettyPrintSchema(block.content)

                // pretty print the json
                block.matches("json") -> reformatJson(block)

                // pretty print the cypher
                CodeBlockPredicate.CYPHER.matches(block) -> block.reformattedContent =
                    CypherUtils.prettyPrintCypher(block.content)
            }
        } catch (ignore: Exception) {
        }
    }

    fun reformatJson(block: CodeBlock) {
        val map = parseJson<Map<String, Any?>>(block.content)
        block.reformattedContent = JsonUtils.prettyPrintJson(map)
        if (block.attributes.containsKey("response")) {

            val hasOrder = block.parent.blocks
                .filterIsInstance<CodeBlock>()
                .any { it.matches("cypher") && it.content.contains("ORDER BY") }
            if (!hasOrder && TestUtils.hasArrayWithMoreThenOneItems(map)) {
                block.adjustedAttributes = block.attributes.toMutableMap()
                    .also { it["ignore-order"] = null }
            }
        }
    }

    fun writeDocument(
        document: Document,
        testFilePath: Path,
    ) {
        val content = document.buildContent { it.reformattedContent ?: it.content }
        Files.write(testFilePath, content.toByteArray())
    }

    fun collectCodeBlocks(node: StructuralNode): List<CodeBlock> {
        return when (node) {
            is CodeBlock -> listOf(node)
            else -> node.blocks.flatMap { collectCodeBlocks(it) }
        }
    }

    companion object {

        @JvmStatic
        fun main(args: Array<String>) {
            AsciidocReformater().run()
        }
    }
}
