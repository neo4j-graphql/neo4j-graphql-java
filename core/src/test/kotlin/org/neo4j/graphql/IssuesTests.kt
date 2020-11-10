package demo.org.neo4j.graphql

import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.utils.CypherTestSuite

class IssuesTests {

    @TestFactory
    fun `github #147`() = CypherTestSuite("issues/gh-147.adoc").generateTests()
}
