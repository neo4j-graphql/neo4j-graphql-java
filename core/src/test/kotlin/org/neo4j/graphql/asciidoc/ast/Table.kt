package org.neo4j.graphql.asciidoc.ast

import org.apache.commons.csv.CSVRecord
import java.net.URI

class Table(
    val uri: URI,
    override val parent: Section,
    val attributes: Map<String, String?>
) : StructuralNode(parent) {

    lateinit var records: List<CSVRecord>

    var caption: String? = null

    var start: Int? = null
    var end: Int? = null

    override fun buildContent(contentExtractor: (CodeBlock) -> String): String {
        val builder = StringBuilder()
        caption?.let {
            builder.append("\n.${it}\n")
        }
        builder.append("[%header,format=csv")
        attributes.forEach { (k, v) ->
            builder.append(",${k}=${v}")
        }
        builder.append("]\n|===\n")
        builder.append(records.first().parser.headerNames.joinToString(",")).append("\n")
        records.forEach { record ->
            builder.append(record.joinToString(",")).append("\n")
        }
        builder.append("|===\n")
        return builder.toString()
    }

}
