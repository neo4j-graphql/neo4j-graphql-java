package org.neo4j.graphql.tools

import org.neo4j.graphql.utils.TestUtils.IS_TEMPLATE
import org.neo4j.graphql.utils.TestUtils.TEST_RESOURCES
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes all template files. Use this before syncing the tests from the JS project to ensure all deleted tests from
 * the JS project are removed.
 */
object JsTemplateDeleter {

    @JvmStatic
    fun main(args: Array<String>) {
        listOf(
            "tck-test-files/schema/v2",
            "tck-test-files/cypher/v2",
            "integration-test-files"
        )
            .forEach {
                val root = Paths.get("$TEST_RESOURCES$it")
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (IS_TEMPLATE.test(path)) {
                            Files.delete(path)
                        }
                        return FileVisitResult.CONTINUE
                    }
                })
            }
    }
}
