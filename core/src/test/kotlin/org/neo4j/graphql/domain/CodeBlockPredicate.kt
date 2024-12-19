package org.neo4j.graphql.domain

import org.neo4j.graphql.asciidoc.ast.CodeBlock


class CodeBlockPredicate private constructor(
    private val language: String,
    private val filter: Map<String, String?> = emptyMap(),
    private val exactly: Boolean = false
) {

    fun matches(codeBlock: CodeBlock) = codeBlock.matches(language, filter, exactly)

    companion object {
        val CYPHER = CodeBlockPredicate("cypher", exactly = true)
        val CYPHER_PARAMS = CodeBlockPredicate("json", exactly = true)
        val GRAPHQL_SOURCE_SCHEMA = CodeBlockPredicate("graphql", mapOf("schema" to "true"))
        val GRAPHQL_AUGMENTED_SCHEMA = CodeBlockPredicate("graphql", mapOf("augmented" to "true"))
        val GRAPHQL_REQUEST = CodeBlockPredicate("graphql", mapOf("request" to "true"))
        val GRAPHQL_REQUEST_VARIABLES = CodeBlockPredicate("json", mapOf("request" to "true"))
        val GRAPHQL_RESPONSE = CodeBlockPredicate("json", mapOf("response" to "true"))
        val QUERY_CONFIG = CodeBlockPredicate("json", mapOf("query-config" to "true"))
        val SCHEMA_CONFIG = CodeBlockPredicate("json", mapOf("schema-config" to "true"))
        val TEST_DATA = CodeBlockPredicate("cypher", mapOf("test-data" to "true"))
        val CUSTOM_RESOLVER = CodeBlockPredicate("kotlin")
    }

}
