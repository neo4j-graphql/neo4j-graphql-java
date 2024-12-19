package org.neo4j.graphql

import apoc.coll.Coll
import apoc.cypher.CypherFunctions
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.neo4j.graphql.factories.CypherTestFactory
import org.neo4j.graphql.utils.TestUtils.createTestsInPath
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
    fun `cypher-directive-tests`() = CypherTestFactory("cypher-directive-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `dynamic-property-tests`() = CypherTestFactory("dynamic-property-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `filter-tests`() = CypherTestFactory("filter-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `relationship-tests`() = CypherTestFactory("relationship-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `movie-tests`() = CypherTestFactory("movie-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `property-tests`() = CypherTestFactory("property-tests.adoc", neo4j).generateTests()

    @TestFactory
    fun `translator-tests1`() = CypherTestFactory("translator-tests1.adoc", neo4j).generateTests()

    @TestFactory
    fun `translator-tests2`() = CypherTestFactory("translator-tests2.adoc", neo4j).generateTests()

    @TestFactory
    fun `translator-tests3`() = CypherTestFactory("translator-tests3.adoc", neo4j).generateTests()

    @TestFactory
    fun `translator-tests-custom-scalars`() =
        CypherTestFactory("translator-tests-custom-scalars.adoc", neo4j).generateTests()

    @TestFactory
    fun `optimized-query-for-filter`() = CypherTestFactory("optimized-query-for-filter.adoc", neo4j).generateTests()

    @TestFactory
    fun `custom-fields`() = CypherTestFactory("custom-fields.adoc", neo4j).generateTests()

    @TestFactory
    fun `test issues`() = createTestsInPath("issues", { CypherTestFactory(it, neo4j).generateTests() })

    @TestFactory
    fun `new cypher tck tests`() =
        createTestsInPath("tck-test-files/cypher", { CypherTestFactory(it, neo4j).generateTests() })

    companion object {
        private val INTEGRATION_TESTS = System.getProperty("neo4j-graphql-java.integration-tests", "false") == "true"
    }
}
