package org.neo4j.graphql

import apoc.coll.Coll
import apoc.cypher.CypherFunctions
import demo.org.neo4j.graphql.utils.TestUtils.createTestsInPath
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.neo4j.graphql.utils.CypherTestSuite
import org.neo4j.harness.Neo4j
import org.neo4j.harness.Neo4jBuilders
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CypherTests {

    private var neo4j: Neo4j? = null

    @BeforeAll
    fun setup() {
        if (INTEGRATION_TESTS) {
            neo4j = Neo4jBuilders
                .newInProcessBuilder(Path.of("target/test-db"))
                .withProcedure(apoc.cypher.Cypher::class.java)
                .withFunction(CypherFunctions::class.java)
                .withProcedure(Coll::class.java)
                .withFunction(Coll::class.java)
                .build()
        }
    }

    @AfterAll
    fun tearDown() {
        neo4j?.close()
    }

    @TestFactory
    fun `cypher-directive-tests`() = CypherTestSuite("cypher-directive-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `dynamic-property-tests`() = CypherTestSuite("dynamic-property-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `filter-tests`() = CypherTestSuite("filter-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `relationship-tests`() = CypherTestSuite("relationship-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `movie-tests`() = CypherTestSuite("movie-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `property-tests`() = CypherTestSuite("property-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `translator-tests1`() = CypherTestSuite("translator-tests1.adoc", neo4j).generateTests()

    @TestFactory
    fun `translator-tests2`() = CypherTestSuite("translator-tests2.adoc", neo4j).generateTests()

    @TestFactory
    fun `translator-tests3`() = CypherTestSuite("translator-tests3.adoc", neo4j).generateTests()

    @TestFactory
    fun `translator-tests-custom-scalars`() = CypherTestSuite("translator-tests-custom-scalars.adoc", neo4j).generateTests()

    @TestFactory
    fun `optimized-query-for-filter`() = CypherTestSuite("optimized-query-for-filter.adoc", neo4j).generateTests()

    @TestFactory
    fun `custom-fields`() = CypherTestSuite("custom-fields.adoc", neo4j).generateTests()

    @TestFactory
    fun `test issues`() =  createTestsInPath("issues", { CypherTestSuite(it, neo4j).generateTests() })

    @TestFactory
    fun `new cypher tck tests v1`() = createTestsInPath("tck-test-files/cypher/v1", { CypherTestSuite(it, neo4j).generateTests() })

    @TestFactory
    fun `new cypher tck tests v2`() = createTestsInPath("tck-test-files/cypher/v2", { CypherTestSuite(it, neo4j).generateTests() })

    companion object {
        private val INTEGRATION_TESTS = System.getProperty("neo4j-graphql-java.integration-tests", "false") == "true"
    }
}
