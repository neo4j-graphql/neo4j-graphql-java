package org.neo4j.graphql

import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.factories.GraphQLSchemaTestFactory
import org.neo4j.graphql.utils.TestUtils.createTestsInPath

class AugmentationTests {

    @TestFactory
    fun `augmentation-tests`() = GraphQLSchemaTestFactory("augmentation-tests.adoc").generateTests()

    @TestFactory
    fun `schema-operations-tests`() = GraphQLSchemaTestFactory("schema-operations-tests.adoc").generateTests()

    @TestFactory
    fun `schema augmentation tests`() =
        createTestsInPath("tck-test-files/schema", { GraphQLSchemaTestFactory(it).generateTests() })
}
