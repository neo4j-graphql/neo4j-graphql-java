package org.neo4j.graphql

import apoc.date.Date
import demo.org.neo4j.graphql.utils.TestUtils.createTestsInPath
import org.junit.jupiter.api.*
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.graphql.utils.CypherTestSuite
import org.neo4j.harness.Neo4j
import org.neo4j.harness.Neo4jBuilders
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CypherTests {

    private var server: Neo4j? = null
    private var driver: Driver? = null


    @BeforeAll
    fun setup() {
        if (INTEGRATION_TESTS) {
            server = Neo4jBuilders
                .newInProcessBuilder(Path.of("target/test-db"))
                .withFunction(Date::class.java)
                .build()
            driver = server?.let { GraphDatabase.driver(it.boltURI(), AuthTokens.none()) }
        }
    }

    @AfterAll
    fun tearDown() {
        driver?.close()
        server?.close()
    }

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `cypher-directive-tests`() = CypherTestSuite("cypher-directive-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `dynamic-property-tests`() = CypherTestSuite("dynamic-property-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `filter-tests`() = CypherTestSuite("filter-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `relationship-tests`() = CypherTestSuite("relationship-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `movie-tests`() = CypherTestSuite("movie-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `property-tests`() = CypherTestSuite("property-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `translator-tests1`() = CypherTestSuite("translator-tests1.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `translator-tests2`() = CypherTestSuite("translator-tests2.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `translator-tests3`() = CypherTestSuite("translator-tests3.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `translator-tests-custom-scalars`() =
        CypherTestSuite("translator-tests-custom-scalars.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `optimized-query-for-filter`() = CypherTestSuite("optimized-query-for-filter.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `custom-fields`() = CypherTestSuite("custom-fields.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `test issues`() = createTestsInPath("issues", { CypherTestSuite(it, driver).generateTests() })

    @TestFactory
    fun `new cypher tck tests v2`() =
        createTestsInPath("tck-test-files/cypher/v2", { CypherTestSuite(it, driver).generateTests() })

    @TestFactory
    fun `integration-tests`() =
        createTestsInPath(
            "integration-test-files",
            { CypherTestSuite(it, driver, createMissingBlocks = false).generateTests() })

    companion object {
        private val INTEGRATION_TESTS = System.getProperty("neo4j-graphql-java.integration-tests", "false") == "true"
    }
}
