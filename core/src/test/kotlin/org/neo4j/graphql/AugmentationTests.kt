package org.neo4j.graphql

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.factories.GraphQLSchemaTestFactory
import org.neo4j.graphql.utils.TestUtils.createTestsInPath

class AugmentationTests {

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `augmentation-tests`() = GraphQLSchemaTestFactory("augmentation-tests.adoc").generateTests()

    @Disabled("This test is disabled because it is not yet migrated")
    @TestFactory
    fun `schema-operations-tests`() = GraphQLSchemaTestFactory("schema-operations-tests.adoc").generateTests()


    @TestFactory
    fun `schema augmentation tests v2`() =
        createTestsInPath("tck-test-files/schema/v2", { GraphQLSchemaTestFactory(it).generateTests() })
}
