package org.neo4j.graphql

import org.junit.Test

class PropertyTest {

    @Test
    fun testTck() {
        AsciiDocTestSuite("property-tests.adoc").runSuite(0)
    }
}