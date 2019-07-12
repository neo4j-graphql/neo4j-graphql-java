package org.neo4j.graphql

import graphql.parser.InvalidSyntaxException
import org.junit.Test

class TranslatorTest {

    private val testSuite = AsciiDocTestSuite("translator-tests1.adoc")

    @Test
    fun testTck() {
        testSuite.runSuite(0)
        AsciiDocTestSuite("translator-tests2.adoc").runSuite()
        AsciiDocTestSuite("translator-tests3.adoc").runSuite()
    }


    @Test(expected = IllegalArgumentException::class) // todo better test
    fun unknownType() {
        testSuite.translate(" { company { name } } ")
    }

    @Test(expected = InvalidSyntaxException::class)
    fun mutation() {
        testSuite.translate(" { createPerson() } ")
    }
}