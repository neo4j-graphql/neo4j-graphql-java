package org.neo4j.graphql

import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.utils.GraphQLSchemaTestSuite

class AugmentationTests {

    @TestFactory
    fun `augmentation-tests`() = GraphQLSchemaTestSuite("augmentation-tests.adoc").generateTests()

    @TestFactory
    fun `schema-operations-tests`() = GraphQLSchemaTestSuite("schema-operations-tests.adoc").generateTests()
}
