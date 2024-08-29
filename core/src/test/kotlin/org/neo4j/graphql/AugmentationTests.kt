package org.neo4j.graphql

import demo.org.neo4j.graphql.utils.TestUtils.createTestsInPath
import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.utils.GraphQLSchemaTestSuite

class AugmentationTests {

    @TestFactory
    fun `augmentation-tests`() = GraphQLSchemaTestSuite("augmentation-tests.adoc").generateTests()

    @TestFactory
    fun `schema-operations-tests`() = GraphQLSchemaTestSuite("schema-operations-tests.adoc").generateTests()

    @TestFactory
    fun `schema augmentation tests v1`() = createTestsInPath("tck-test-files/schema/v1", { GraphQLSchemaTestSuite(it).generateTests() })

    @TestFactory
    fun `schema augmentation tests v2`() =
        createTestsInPath("tck-test-files/schema/v2", { GraphQLSchemaTestSuite(it).generateTests() })
}
