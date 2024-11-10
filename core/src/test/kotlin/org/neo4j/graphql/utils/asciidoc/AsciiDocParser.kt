package demo.org.neo4j.graphql.utils.asciidoc

import demo.org.neo4j.graphql.utils.asciidoc.ast.*
import java.io.File
import java.net.URI
import java.util.regex.Pattern
import javax.ws.rs.core.UriBuilder

class AsciiDocParser(
    fileName: String
) {

    private val file = File(AsciiDocParser::class.java.getResource("/$fileName")?.toURI()!!)
    private val srcLocation = File("src/test/resources/", fileName).toURI()

    private var root = Document(srcLocation)
    private var currentSection: Section = root
    private var currentDepth: Int = 0


    fun parse(): Document {
        val lines = file.readLines()
        var title: String?

        var insideCodeblock = false
        var offset = 0

        val fileContent = StringBuilder()

        root = Document(srcLocation)
        currentSection = root
        currentDepth = 0
        var caption: String? = null

        var currentCodeBlock: CodeBlock? = null
        var content = StringBuilder()


        loop@ for ((lineNr, line) in lines.withIndex()) {
            fileContent.append(line).append('\n')

            if (line.startsWith("#") || line.startsWith("//")) {
                offset += line.length + 1
                continue
            }

            val headlineMatcher = HEADLINE_PATTERN.matcher(line)

            when {

                headlineMatcher.matches() -> {
                    addBlock(content)
                    val depth = headlineMatcher.group(1).length
                    title = headlineMatcher.group(2)
                    val uri = UriBuilder.fromUri(srcLocation).queryParam("line", lineNr + 1).build()
                    startSection(title, uri, depth)
                }

                line.startsWith(".") && !insideCodeblock -> {
                    caption = line.substring(1).trim()
                }

                line.startsWith("[source,") -> {
                    addBlock(content)
                    val uri = UriBuilder.fromUri(srcLocation).queryParam("line", lineNr + 1).build()
                    currentCodeBlock = CodeBlock.parseMeta(currentSection, uri, line).also {
                        it.caption = caption
                        currentSection.blocks.add(it)
                    }
                    caption = null
                }

                line == "'''" -> {
                    addBlock(content)
                    currentSection.blocks.add(ThematicBreak())
                }

                line == "----" -> {
                    insideCodeblock = !insideCodeblock
                    if (insideCodeblock) {
                        currentCodeBlock?.start = offset + line.length + 1
                        content = StringBuilder()
                    } else if (currentCodeBlock != null) {
                        currentCodeBlock.end = offset
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

    private fun addBlock(content: StringBuilder) {
        val str = content.toString()
        if (str.trim().isNotEmpty()) {
            currentSection.let { it.blocks.add(Block(it, str)) }
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
