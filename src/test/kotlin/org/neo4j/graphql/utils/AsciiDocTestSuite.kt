package org.neo4j.graphql.utils

import org.codehaus.jackson.map.ObjectMapper
import java.io.File

open class AsciiDocTestSuite {
    class ParsedFile(
            var schema: String,
            var file: File,
            val tests: MutableList<ParsedBlock>
    )

    class ParsedBlock(
            var title: String? = null,
            var line: Int = 0,
            var ignore: Boolean = false,
            val codeBlocks: MutableMap<String, StringBuilder> = mutableMapOf()
    )

    companion object {
        val MAPPER = ObjectMapper()

        fun parse(fileName: String, blocks: LinkedHashSet<String>): ParsedFile {
            val file = File(AsciiDocTestSuite::class.java.getResource("/$fileName").toURI())
            val lines = file.readLines()
            val terminatorElement = blocks.last()
            val tests: MutableList<ParsedBlock> = mutableListOf()

            var schema: String? = null
            var lastTitle: String? = null
            var titleCount = 1
            var current: StringBuilder? = null

            var testSet = ParsedBlock()
            var inside = false
            for ((lineNr, line) in lines.withIndex()) {
                if (line.startsWith("#") || line.startsWith("//")) {
                    continue
                }
                when {
                    line == "[source,graphql,schema=true]" -> schema = ""
                    blocks.contains(line) -> {
                        current = StringBuilder()
                        testSet.codeBlocks[line] = current
                    }
                    line == "----" -> {
                        if (testSet.codeBlocks[terminatorElement]?.isNotEmpty() == true) {
                            tests.add(testSet)
                            if (testSet.title == null) {
                                testSet.title = lastTitle + " " + ++titleCount
                            } else {
                                titleCount = 1
                                lastTitle = testSet.title
                            }
                            testSet = ParsedBlock()
                        }
                        inside = !inside
                    }
                    line.startsWith("=== ") -> {
                        testSet.title = line.substring(4)
                        testSet.line = lineNr + 1
                    }
                    line.startsWith("CAUTION:") -> testSet.ignore = true
                    inside -> when {
                        current != null -> current.append(line).append("\n")
                        schema != null -> schema += line + "\n"
                    }
                }
            }
            return ParsedFile(schema ?: throw IllegalStateException("no schema found"),
                    File("src/test/resources/$fileName").absoluteFile, tests)
        }

        private fun fixNumber(v: Any?): Any? = when (v) {
            is Float -> v.toDouble()
            is Int -> v.toLong()
            is Iterable<*> -> v.map { fixNumber(it) }
            is Sequence<*> -> v.map { fixNumber(it) }
            is Map<*, *> -> v.mapValues { fixNumber(it.value) }
            else -> v
        }

        fun fixNumbers(params: Map<String, Any?>) = params.mapValues { (_, v) -> fixNumber(v) }

        fun String.parseJsonMap(): Map<String, Any?> = this.let {
            @Suppress("UNCHECKED_CAST")
            MAPPER.readValue(this, Map::class.java) as Map<String, Any?>
        }

        fun String.normalize(): String = this
            .replace(Regex("\\s+"), " ")
            .replace(Regex(",(\\S)"), ", $1")
            .replace(Regex("\\{(\\S)"), "{ $1")
            .replace(Regex("(\\S)}"), "$1 }")
            .replace(Regex(":(\\S)"), ": $1")
    }

}
