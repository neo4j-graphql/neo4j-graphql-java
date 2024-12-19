package org.neo4j.graphql.domain

import org.neo4j.graphql.asciidoc.ast.CodeBlock
import org.neo4j.graphql.asciidoc.ast.Section
import org.neo4j.graphql.asciidoc.ast.Table
import org.neo4j.graphql.domain.CodeBlockPredicate.Companion.CUSTOM_RESOLVER
import org.neo4j.graphql.domain.CodeBlockPredicate.Companion.CYPHER
import org.neo4j.graphql.domain.CodeBlockPredicate.Companion.CYPHER_PARAMS
import org.neo4j.graphql.domain.CodeBlockPredicate.Companion.GRAPHQL_AUGMENTED_SCHEMA
import org.neo4j.graphql.domain.CodeBlockPredicate.Companion.GRAPHQL_REQUEST
import org.neo4j.graphql.domain.CodeBlockPredicate.Companion.GRAPHQL_REQUEST_VARIABLES
import org.neo4j.graphql.domain.CodeBlockPredicate.Companion.GRAPHQL_RESPONSE
import org.neo4j.graphql.domain.CodeBlockPredicate.Companion.GRAPHQL_SOURCE_SCHEMA
import org.neo4j.graphql.domain.CodeBlockPredicate.Companion.QUERY_CONFIG
import org.neo4j.graphql.domain.CodeBlockPredicate.Companion.SCHEMA_CONFIG
import org.neo4j.graphql.domain.CodeBlockPredicate.Companion.TEST_DATA
import kotlin.reflect.KMutableProperty1

data class TestCase(
    val setup: Setup,
    var cypher: CodeBlock? = null,
    var cypherParams: CodeBlock? = null,
    var graphqlRequest: CodeBlock? = null,
    var graphqlRequestVariables: CodeBlock? = null,
    var graphqlResponse: CodeBlock? = null,
    var graphqlResponseAssertions: Table? = null,
    var queryConfig: CodeBlock? = null,
    var augmentedSchema: CodeBlock? = null,
) {

    data class Setup(
        val schema: CodeBlock?,
        val schemaConfig: CodeBlock?,
        val testData: List<CodeBlock>,
        val customResolver: CodeBlock?,
    ) {
        constructor(section: Section) : this(
            section.findSetupCodeBlocks(GRAPHQL_SOURCE_SCHEMA).firstOrNull(),
            section.findSetupCodeBlocks(SCHEMA_CONFIG).firstOrNull(),
            section.findSetupCodeBlocks(TEST_DATA),
            section.findSetupCodeBlocks(CUSTOM_RESOLVER).firstOrNull()
        )
    }

    fun parseCodeBlock(codeBlock: CodeBlock) {
        when {
            CYPHER.matches(codeBlock) -> init(codeBlock, TestCase::cypher)
            CYPHER_PARAMS.matches(codeBlock) -> init(codeBlock, TestCase::cypherParams)
            GRAPHQL_REQUEST.matches(codeBlock) -> init(codeBlock, TestCase::graphqlRequest)
            GRAPHQL_REQUEST_VARIABLES.matches(codeBlock) -> init(codeBlock, TestCase::graphqlRequestVariables)
            GRAPHQL_RESPONSE.matches(codeBlock) -> init(codeBlock, TestCase::graphqlResponse)
            QUERY_CONFIG.matches(codeBlock) -> init(codeBlock, TestCase::queryConfig)
            GRAPHQL_AUGMENTED_SCHEMA.matches(codeBlock) -> init(codeBlock, TestCase::augmentedSchema)
        }
    }

    fun parseTable(table: Table) {
        if (table.attributes.containsKey("response")) {
            graphqlResponseAssertions = table
        }
    }

    private fun <V> init(current: V, prop: KMutableProperty1<TestCase, V?>) {
        check(prop.get(this) == null) { "Only one ${prop.name} block is allowed per test case" }
        prop.set(this, current)
    }
}
