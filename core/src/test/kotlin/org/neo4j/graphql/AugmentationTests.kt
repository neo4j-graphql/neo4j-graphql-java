package org.neo4j.graphql

import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.utils.GraphQLSchemaTestSuite
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Stream

class AugmentationTests {

    @TestFactory
    fun `augmentation-tests`() = GraphQLSchemaTestSuite("augmentation-tests.adoc").generateTests()

    @TestFactory
    fun `schema-operations-tests`() = GraphQLSchemaTestSuite("schema-operations-tests.adoc").generateTests()

    @TestFactory
    fun `schema augmentation tests`(): Stream<DynamicNode>? = Files
        .list(Paths.get("src/test/resources/tck-test-files/schema"))
        .map {
            DynamicContainer.dynamicContainer(
                    it.fileName.toString(),
                    it.toUri(),
                    GraphQLSchemaTestSuite("tck-test-files/schema/${it.fileName}").generateTests()
            )
        }
}
