package org.neo4j.graphql

import org.junit.Test

class CypherDirectiveTest {


    private val testSuite = AsciiDocTestSuite("cypher-directive-tests.adoc")

    @Test
    fun testTck() {
        testSuite.runSuite()
    }
}