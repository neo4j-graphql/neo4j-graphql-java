package org.neo4j.graphql

import demo.org.neo4j.graphql.utils.asciidoc.ast.CodeBlock
import demo.org.neo4j.graphql.utils.asciidoc.ast.Section
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.utils.AsciiDocTestSuite
import java.util.stream.Stream

class TranslatorExceptionTests : AsciiDocTestSuite<CodeBlock>("translator-tests1.adoc", emptyList()) {

    override fun createTestCase(section: Section): CodeBlock? {
        return findSetupCodeBlocks(section, "graphql", mapOf("schema" to "true")).firstOrNull() ?: return null
    }

    override fun createTests(testCase: CodeBlock, section: Section, ignoreReason: String?): List<DynamicNode> {
        if (section.title != "Tests") {
            return emptyList()
        }
        return listOf(
            DynamicTest.dynamicTest("unknownType") {
                Assertions.assertThrows(InvalidQueryException::class.java) {
                    Translator(SchemaBuilder.buildSchema(testCase.content)).translate(
                        """
                    {
                      company {
                        name
                      }
                    }
                    """
                    )
                }
            },
            DynamicTest.dynamicTest("mutation") {
                Assertions.assertThrows(InvalidQueryException::class.java) {
                    Translator(SchemaBuilder.buildSchema(testCase.content)).translate(
                        """
                    {
                      createPerson()
                    }
                    """.trimIndent()
                    )
                }
            }
        )
    }

    @TestFactory
    fun createTests(): Stream<DynamicNode> = generateTests()
}
