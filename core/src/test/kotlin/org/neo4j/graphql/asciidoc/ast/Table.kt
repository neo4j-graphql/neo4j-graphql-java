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

}
