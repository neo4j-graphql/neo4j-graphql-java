package demo.org.neo4j.graphql

import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.utils.GraphQLSchemaTestSuite

class AugmentationTest {

    @TestFactory
    fun `augmentation-tests`() = GraphQLSchemaTestSuite("augmentation-tests.adoc").run()
}
