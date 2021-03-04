package org.neo4j.graphql

import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.TestFactory
import org.neo4j.graphql.utils.CypherTestSuite
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Stream

class IssuesTests {

    @TestFactory
    fun `test issues`(): Stream<DynamicNode>? = Files
        .list(Paths.get("src/test/resources/issues"))
        .map {
            DynamicContainer.dynamicContainer(
                    it.fileName.toString(),
                    it.toUri(),
                    CypherTestSuite("issues/${it.fileName}").generateTests()
            )
        }
}
