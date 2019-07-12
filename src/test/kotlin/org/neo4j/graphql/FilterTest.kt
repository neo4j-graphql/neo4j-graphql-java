package org.neo4j.graphql

import org.junit.Test

class FilterTest {

    private val testSuite = AsciiDocTestSuite("filter-tests.adoc")

    @Test
    fun testTck() {
        testSuite.runSuite(59)
    }

}