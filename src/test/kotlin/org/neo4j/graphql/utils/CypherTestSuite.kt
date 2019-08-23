package org.neo4j.graphql.utils

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.Translator

class CypherTestSuite(fileName: String) : AsciiDocTestSuite() {
    val schema: String

    class TestRun(
            private val suite: CypherTestSuite,
            val title: String?,
            private val request: String,
            private val cypher: String,
            private val cypherParams: Map<String, Any?> = emptyMap(),
            private val requestParams: Map<String, Any?> = emptyMap(),
            private val ignore: Boolean) {

        fun run(contextProvider: ((requestParams: Map<String, Any?>) -> Translator.Context?)?) {
            println(title)
            try {
                val ctx = contextProvider?.let { it(requestParams) } ?: Translator.Context()
                val result = suite.translate(request, requestParams, ctx)
                println(result.query)
                Assertions.assertEquals(this.cypher.normalize(), result.query.normalize())
                Assertions.assertEquals(fixNumbers(cypherParams), fixNumbers(result.params)) {
                    "expected: ${MAPPER.writeValueAsString(cypherParams)}\n" +
                            "Actual ${MAPPER.writeValueAsString(result.params)}"
                }
            } catch (e: Throwable) {
                if (ignore) {
                    Assumptions.assumeFalse(true, e.message)
                } else {
                    throw e
                }
            }
        }
    }

    val tests: List<TestRun>

    init {
        val result = parse(fileName, linkedSetOf("[source,graphql]", "[source,json,request=true]", "[source,json]", "[source,cypher]"))
        schema = result.schema
        tests = result.tests.map {
            TestRun(this,
                    it.title,
                    it.codeBlocks["[source,graphql]"]?.trim()?.toString()
                            ?: throw IllegalStateException("missing graphql for ${it.title}"),
                    it.codeBlocks["[source,cypher]"]?.trim()?.toString()
                            ?: throw IllegalStateException("missing cypher query for ${it.title}"),
                    it.codeBlocks["[source,json]"]?.toString()?.parseJsonMap() ?: emptyMap(),
                    it.codeBlocks["[source,json,request=true]"]?.toString()?.parseJsonMap() ?: emptyMap(),
                    it.ignore
            )
        }
    }

    fun translate(query: String, requestParams: Map<String, Any?> = emptyMap(), ctx: Translator.Context = Translator.Context()): Translator.Cypher {
        return Translator(SchemaBuilder.buildSchema(schema))
            .translate(query, requestParams, ctx)
            .first()
    }

    fun run(contextProvider: ((requestParams: Map<String, Any?>) -> Translator.Context)? = null): List<DynamicTest> {
        return tests.map { DynamicTest.dynamicTest(it.title) { it.run(contextProvider) } }
    }
}