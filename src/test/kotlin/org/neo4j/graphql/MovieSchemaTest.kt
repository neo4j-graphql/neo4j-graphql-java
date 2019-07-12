package demo.org.neo4j.graphql

import org.junit.Test
import org.neo4j.graphql.AsciiDocTestSuite


class MovieSchemaTest {

    @Test
    fun testTck() {
        AsciiDocTestSuite("movie-tests.adoc").runSuite(0, true)
    }
}