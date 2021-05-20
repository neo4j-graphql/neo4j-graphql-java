package demo.org.neo4j.graphql.utils

import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Stream

object TestUtils {

    fun createTestsInPath(path: String, factory: (testFile: String) -> Stream<DynamicNode>): Stream<DynamicNode>? = Files
        .list(Paths.get("src/test/resources/$path"))
        .sorted()
        .map {
            val tests = if (Files.isDirectory(it)) {
                createTestsInPath("$path/${it.fileName}", factory)
            } else {
                factory("$path/${it.fileName}")
            }
            DynamicContainer.dynamicContainer(it.fileName.toString(), it.toUri(), tests)
        }
}
