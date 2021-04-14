package org.neo4j.graphql

import apoc.coll.Coll
import apoc.cypher.CypherFunctions
import org.junit.jupiter.api.*
import org.neo4j.graphql.utils.CypherTestSuite
import org.neo4j.harness.Neo4j
import org.neo4j.harness.Neo4jBuilders
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream

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
    fun `test issues`(): Stream<DynamicNode>? = Files
        .list(Paths.get("src/test/resources/issues"))
        .map {
            DynamicContainer.dynamicContainer(
                    it.fileName.toString(),
                    it.toUri(),
                    CypherTestSuite("issues/${it.fileName}", neo4j).generateTests()
            )
        }

    companion object {
       private val INTEGRATION_TESTS = System.getProperty("neo4j-graphql-java.integration-tests", "false") == "true"
    }
}
