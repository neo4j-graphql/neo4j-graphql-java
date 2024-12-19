package org.neo4j.graphql

import apoc.date.Date
import org.junit.jupiter.api.*
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.graphql.factories.CypherTestFactory
import org.neo4j.graphql.utils.TestUtils.createTestsInPath
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
    fun `cypher-directive-tests`() = CypherTestFactory("cypher-directive-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `dynamic-property-tests`() = CypherTestFactory("dynamic-property-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `filter-tests`() = CypherTestFactory("filter-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `relationship-tests`() = CypherTestFactory("relationship-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `movie-tests`() = CypherTestFactory("movie-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `property-tests`() = CypherTestFactory("property-tests.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `translator-tests1`() = CypherTestFactory("translator-tests1.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `translator-tests2`() = CypherTestFactory("translator-tests2.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `translator-tests3`() = CypherTestFactory("translator-tests3.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `translator-tests-custom-scalars`() =
        CypherTestFactory("translator-tests-custom-scalars.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `optimized-query-for-filter`() = CypherTestFactory("optimized-query-for-filter.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `custom-fields`() = CypherTestFactory("custom-fields.adoc", driver).generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `test issues`() = createTestsInPath("issues", { CypherTestFactory(it, driver).generateTests() })

    @TestFactory
    fun `new cypher tck tests v2`() =
        createTestsInPath("tck-test-files/cypher/v2", { CypherTestFactory(it, driver).generateTests() })

    @TestFactory
    fun `integration-tests`() =
        createTestsInPath(
            "integration-test-files",
            { CypherTestFactory(it, driver, createMissingBlocks = false).generateTests() })

    companion object {
        private val INTEGRATION_TESTS = System.getProperty("neo4j-graphql-java.integration-tests", "false") == "true"
    }
}
