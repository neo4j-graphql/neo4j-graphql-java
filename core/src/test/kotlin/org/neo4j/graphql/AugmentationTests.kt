package org.neo4j.graphql

import demo.org.neo4j.graphql.utils.TestUtils.createTestsInPath
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.utils.GraphQLSchemaTestSuite

class AugmentationTests {

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `augmentation-tests`() = GraphQLSchemaTestSuite("augmentation-tests.adoc").generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `schema-operations-tests`() = GraphQLSchemaTestSuite("schema-operations-tests.adoc").generateTests()


    @TestFactory
    fun `schema augmentation tests v2`() =
        createTestsInPath("tck-test-files/schema/v2", { GraphQLSchemaTestSuite(it).generateTests() })
}
