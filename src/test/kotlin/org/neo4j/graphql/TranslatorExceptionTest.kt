package org.neo4j.graphql

import graphql.parser.InvalidSyntaxException
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.neo4j.graphql.utils.CypherTestSuite

class TranslatorExceptionTest {

    private val testSuite = CypherTestSuite("translator-tests1.adoc")

    @Test
    fun unknownType() {
        // todo better test
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            testSuite.translate("""
            {
              company {
                name
              }
            }
            """.trimIndent())
        }
    }

    @Test
    fun mutation() {
        Assertions.assertThrows(InvalidSyntaxException::class.java) {
            testSuite.translate("""
            {
              createPerson()
            }
            """.trimIndent())
        }
    }
}