package org.neo4j.graphql

import org.junit.Test

class FilterTest {

    @Test
    fun testTck() {
        AsciiDocTestSuite("filter-tests.adoc").runSuite(59)
    }

}