package org.neo4j.graphql.asciidoc.ast

import java.net.URI

class Document(
    uri: URI,
) : Section(uri.path.substringAfterLast('/'), uri, null) {

    lateinit var content: String

}
