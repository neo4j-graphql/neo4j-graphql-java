package org.neo4j.graphql

import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.utils.CypherTestSuite

class CypherTests {

    @TestFactory
    fun `cypher-directive-tests`() = CypherTestSuite("cypher-directive-tests.adoc").run()

    @TestFactory
    fun `filter-tests`() = CypherTestSuite("filter-tests.adoc").run()

    @TestFactory
    fun `movie-tests`() = CypherTestSuite("movie-tests.adoc").run()

    @TestFactory
    fun `property-tests`() = CypherTestSuite("property-tests.adoc").run()

    @TestFactory
    fun `translator-tests1`() = CypherTestSuite("translator-tests1.adoc").run()

    @TestFactory
    fun `translator-tests2`() = CypherTestSuite("translator-tests2.adoc").run()

    @TestFactory
    fun `translator-tests3`() = CypherTestSuite("translator-tests3.adoc").run()
}