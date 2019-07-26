package org.neo4j.graphql.utils

import org.codehaus.jackson.map.ObjectMapper
import java.io.File

open class AsciiDocTestSuite {
    class ParsedFile(var schema: String, val tests: MutableList<ParsedBlock>)
    class ParsedBlock(var title: String? = null, var ignore: Boolean = false, val codeBlocks: MutableMap<String, StringBuilder> = mutableMapOf())

    companion object {
        private val MAPPER = ObjectMapper()

        fun parse(fileName: String, blocks: LinkedHashSet<String>): ParsedFile {
            val lines = File(AsciiDocTestSuite::class.java.getResource("/$fileName").toURI())
                .readLines()
                .filterNot { it.startsWith("#") || it.isBlank() || it.startsWith("//") }
            val terminatorElement = blocks.last()
            val tests: MutableList<ParsedBlock> = mutableListOf()

            var schema: String? = null
            var lastTitle: String? = null
            var titleCount: Int = 1
            var current: StringBuilder? = null

            var testSet = ParsedBlock()
            var inside = false
            for (line in lines) {
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
                    line.startsWith("=== ") -> testSet.title = line.substring(4)
                    line.startsWith("CAUTION:") -> testSet.ignore = true
                    inside -> when {
                        current != null -> current.append(" ").append(line.trim())
                        schema != null -> schema += line.trim() + "\n"
                    }
                }
            }
            return ParsedFile(schema ?: throw IllegalStateException("no schema found"), tests)
        }

        private fun fixNumber(v: Any?): Any? = when (v) {
            is Float -> v.toDouble(); is Int -> v.toLong(); else -> v
        }

        fun fixNumbers(params: Map<String, Any?>) = params.mapValues { (_, v) ->
            when (v) {
                is List<*> -> v.map { fixNumber(it) }; else -> fixNumber(v)
            }
        }

        fun String.parseJsonMap(): Map<String, Any?> = this.let {
            @Suppress("UNCHECKED_CAST")
            MAPPER.readValue(this, Map::class.java) as Map<String, Any?>
        }

        fun String.normalize(): String = this.replace(Regex("\\s+"), " ")
    }

}