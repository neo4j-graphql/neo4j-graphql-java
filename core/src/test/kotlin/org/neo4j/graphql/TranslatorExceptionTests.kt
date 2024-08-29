package org.neo4j.graphql

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.utils.AsciiDocTestSuite
import java.util.stream.Stream

class TranslatorExceptionTests : AsciiDocTestSuite("translator-tests1.adoc") {

    @TestFactory
    fun createTests(): Stream<DynamicNode> {
        return generateTests()
    }

    override fun schemaTestFactory(schema: String): List<DynamicNode> {
        val translator = Translator(SchemaBuilder.buildSchema(schema))
        return listOf(
            DynamicTest.dynamicTest("unknownType") {
                Assertions.assertThrows(InvalidQueryException::class.java) {
                    translator.translate(
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
                    translator.translate(
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
}
