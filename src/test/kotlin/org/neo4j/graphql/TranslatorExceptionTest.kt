package org.neo4j.graphql

import graphql.parser.InvalidSyntaxException
import org.junit.Test

class TranslatorExceptionTest {

    private val testSuite = AsciiDocTestSuite("translator-tests1.adoc")

    @Test(expected = IllegalArgumentException::class) // todo better test
    fun unknownType() {
        testSuite.translate(" { company { name } } ")
    }

    @Test(expected = InvalidSyntaxException::class)
    fun mutation() {
        testSuite.translate(" { createPerson() } ")
    }
}