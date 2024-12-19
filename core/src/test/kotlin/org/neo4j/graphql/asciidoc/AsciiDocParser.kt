package org.neo4j.graphql.asciidoc

import org.apache.commons.csv.CSVFormat
import org.neo4j.graphql.asciidoc.ast.*
import java.net.URI
import java.nio.file.Path
import java.util.regex.Pattern
import javax.ws.rs.core.UriBuilder
import kotlin.io.path.readLines

class AsciiDocParser(
    private val file: Path
) {

    private var root = Document(file.toUri())
    private var currentSection: Section = root
    private var currentDepth: Int = 0

    fun parse(): Document {
        return parseLines(file.readLines())
    }

    fun parseContent(content: String): Document {
        return parseLines(content.lines())
    }

    private fun parseLines(lines: List<String>): Document {
        var title: String?

        var insideCodeblock = false
        var insideTable = false
        var offset = 0

        val fileContent = StringBuilder()

        root = Document(file.toUri())
        currentSection = root
        currentDepth = 0
        var caption: String? = null

        var currentCodeBlock: CodeBlock? = null
        var currentTable: Table? = null
        var content = StringBuilder()


        loop@ for ((lineNr, line) in lines.withIndex()) {
            fileContent.append(line).append('\n')

            val headlineMatcher = HEADLINE_PATTERN.matcher(line)

            when {

                headlineMatcher.matches() -> {
                    addBlock(content)
                    val depth = headlineMatcher.group(1).length
                    title = headlineMatcher.group(2)
                    val uri = uriWithLineNr(lineNr)
                    startSection(title, uri, depth)
                }

                line.startsWith(".") && !insideCodeblock -> {
                    caption = line.substring(1).trim()
                }

                line.startsWith("[%header,format=csv") -> {
                    addBlock(content)
                    val uri = uriWithLineNr(lineNr)

                    val parts = line.substring(19, line.indexOf("]")).trim().split(",").filter { it.isNotBlank() }
                    val attributes = parts.slice(parts.indices).map {
                        val attributeParts = it.split("=")
                        attributeParts[0] to attributeParts.getOrNull(1)
                    }.toMap()

                    currentTable = Table(uri, currentSection, attributes).also {
                        it.caption = caption
                        currentSection.blocks.add(it)
                    }
                    caption = null
                }

                line.startsWith("[source,") -> {
                    addBlock(content)
                    val uri = uriWithLineNr(lineNr)

                    val parts = line.substring(8, line.indexOf("]")).trim().split(",")
                    val language = parts[0]
                    val attributes = parts.slice(1..<parts.size).map {
                        val attributeParts = it.split("=")
                        attributeParts[0] to attributeParts.getOrNull(1)
                    }.toMap()

                    currentCodeBlock = CodeBlock(uri, language, currentSection, attributes).also {
                        it.caption = caption
                        currentSection.blocks.add(it)
                    }
                    caption = null
                }

                line == "'''" -> {
                    addBlock(content)
                    currentSection.blocks.add(ThematicBreak())
                }

                line == "|===" -> {
                    insideTable = !insideTable
                    if (insideTable) {
                        currentTable?.start = offset + line.length + 1
                        content = StringBuilder()
                    } else if (currentTable != null) {
                        currentTable.end = offset
                        currentTable.records = CSVFormat.Builder.create().setHeader()
                            .setSkipHeaderRecord(true)
                            .build()
                            .parse(content.toString().trim().reader()).use { it.records }
                        currentTable = null
                        content = StringBuilder()
                    }
                }

                line == "----" -> {
                    insideCodeblock = !insideCodeblock
                    if (insideCodeblock) {
                        content = StringBuilder()
                    } else if (currentCodeBlock != null) {
                        currentCodeBlock.content = content.toString().trim()
                        currentCodeBlock = null
                        content = StringBuilder()
                    }
                }

                else -> {
                    content.append(line).append("\n")
                }
            }
            offset += line.length + 1 // +1 b/c of newline
        }
        addBlock(content)
        root.content = fileContent.toString()
        return root
    }

    private fun uriWithLineNr(lineNr: Int): URI =
        UriBuilder.fromUri(file.toUri()).queryParam("line", lineNr + 1).build()

    private fun addBlock(content: StringBuilder) {
        val str = content.toString()
        if (str.isNotBlank()) {
            currentSection.let { it.blocks.add(Block(it, str.trimEnd())) }
        }
        content.clear()
    }

    private fun startSection(
        title: String, uri: URI, depth: Int
    ) {

        val parent = when {
            depth > currentDepth -> currentSection
            depth == currentDepth -> currentSection.parent
            else -> {
                var parent = currentSection.parent
                for (i in 0 until currentDepth - depth) {
                    parent = parent?.parent
                }
                parent
            }
        } ?: error("cannot create sub-level on null")
        currentSection = Section(title, uri, parent)
            .also { parent.blocks.add(it) }

        currentDepth = depth
    }

    companion object {
        private val HEADLINE_PATTERN: Pattern = Pattern.compile("^(=+) (.*)$")
    }
}
