package org.neo4j.graphql.utils

import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.Predicate
import java.util.stream.Stream
import kotlin.io.path.extension

object TestUtils {

    const val TEST_RESOURCES = "src/test/resources/"
    val IS_TEMPLATE = Predicate<Path> { it.fileName.toString().endsWith(".js.adoc") }
    val IS_TEST_FILE = Predicate<Path> { it.extension == "adoc" && !IS_TEMPLATE.test(it) }

    fun createTestsInPath(path: String, factory: (file: Path) -> Stream<DynamicNode>): Stream<DynamicNode> =
        Files
            .list(Paths.get(TEST_RESOURCES, path))
            .sorted()
            .map {
                val tests = if (Files.isDirectory(it)) {
                    createTestsInPath("$path/${it.fileName}", factory)
                } else if (IS_TEST_FILE.test(it)) {
                    factory(it)
                } else {
                    Stream.empty()
                }
                DynamicContainer.dynamicContainer(it.fileName.toString(), it.toUri(), tests)
            }

    fun hasArrayWithMoreThenOneItems(value: Any): Boolean {
        when (value) {
            is Map<*, *> -> {
                return value.any {
                    val mapValue = it.value
                    mapValue != null && hasArrayWithMoreThenOneItems(mapValue)
                }
            }

            is Collection<*> -> {
                return value.size > 1 || value.filterNotNull().any { hasArrayWithMoreThenOneItems(it) }
            }
        }
        return false
    }
}
