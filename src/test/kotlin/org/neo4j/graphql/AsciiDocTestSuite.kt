package org.neo4j.graphql

import org.codehaus.jackson.map.ObjectMapper
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DynamicTest
import java.io.File

class AsciiDocTestSuite(fileName: String) {

    class TestRun(
            private val suite: AsciiDocTestSuite,
            val title: String?,
            private val request: String,
            private val cypher: String,
            private val cypherParams: Map<String, Any?> = emptyMap(),
            private val requestParams: Map<String, Any?> = emptyMap(),
            private val ignore: Boolean) {

        fun run(contextModifier: (Translator.Context) -> Translator.Context = { it }) {
            println(title)
            try {
                suite.runTest(this.request, this.cypher, this.cypherParams, this.requestParams, contextModifier)
            } catch (e: Throwable) {
                if (ignore) {
                    Assumptions.assumeFalse(true, e.message)
                } else {
                    throw e
                }
            }
        }
    }

    val schema: String
    val tests: List<TestRun>

    init {
        this.tests = mutableListOf()
        val lines = File(javaClass.getResource("/$fileName").toURI())
            .readLines()
            .filterNot { it.startsWith("#") || it.isBlank() || it.startsWith("//") }
        var cypher: String? = null
        var graphql: String? = null
        var requestParams: String? = null
        var cypherParams: String? = null
        var title: String? = null
        var schema: String? = null
        var inside = false
        var ignore = false
        // TODO possibly turn stream into type/data pairs adding to the last element in a reduce and add a new element when context changes
        for (line in lines) {
            when (line) {
                "[source,graphql,schema=true]" -> schema = ""
                "[source,graphql]" -> graphql = ""
                "[source,cypher]" -> cypher = ""
                "[source,json,request=true]" -> requestParams = ""
                "[source,json]" -> cypherParams = ""
                "----" -> {
                    if (graphql?.isNotBlank() == true && cypher?.isNotBlank() == true) {
                        this.tests.add(TestRun(
                                this,
                                title,
                                graphql.trim(),
                                cypher.trim(),
                                cypherParams?.parseJsonMap() ?: emptyMap(),
                                requestParams?.parseJsonMap() ?: emptyMap(),
                                ignore))
                        graphql = null
                        cypher = null
                        cypherParams = null
                        requestParams = null
                        ignore = false
                    }
                    inside = !inside
                }
                else -> {
                    if (line.startsWith("=== "))
                        title = line.substring(4)
                    if (line.startsWith("CAUTION:"))
                        ignore = true
                    if (inside) when {
                        cypher != null -> cypher += " " + line.trim()
                        cypherParams != null -> cypherParams += line.trim()
                        requestParams != null -> requestParams += line.trim()
                        graphql != null -> graphql += " " + line.trim()
                        schema != null -> schema += line.trim() + "\n"
                    }
                }
            }
            //            println("line '$line' GQL '$graphql' Cypher '$cypher'")
        }
        this.schema = schema ?: throw IllegalStateException("no schema found")
    }

    @Suppress("SameParameterValue")
    fun runTest(graphQLQuery: String,
            expectedCypherQuery: String,
            cypherParams: Map<String, Any?> = emptyMap(),
            requestParams: Map<String, Any?> = emptyMap(),
            contextModifier: (Translator.Context) -> Translator.Context = { it }) {
        val result = translate(graphQLQuery, requestParams, contextModifier)
        println(result.query)
        Assertions.assertEquals(expectedCypherQuery.normalize(), result.query.normalize())
        Assertions.assertEquals(fixNumbers(cypherParams), fixNumbers(result.params)) { "$cypherParams IN ${result.params}" }
    }

    fun translate(query: String,
            requestParams: Map<String, Any?> = emptyMap(),
            contextModifier: (Translator.Context) -> Translator.Context = { it }): Translator.Cypher {
        return Translator(SchemaBuilder.buildSchema(schema))
            .translate(query, requestParams, contextModifier.invoke(Translator.Context(params = requestParams)))
            .first()
    }

    fun run(contextModifier: (Translator.Context) -> Translator.Context = { it }): List<DynamicTest> {
        return tests.map { DynamicTest.dynamicTest(it.title) { it.run(contextModifier) } }
    }

    companion object {
        private val MAPPER = ObjectMapper()

        private fun fixNumber(v: Any?): Any? = when (v) {
            is Float -> v.toDouble(); is Int -> v.toLong(); else -> v
        }

        private fun fixNumbers(params: Map<String, Any?>) = params.mapValues { (_, v) ->
            when (v) {
                is List<*> -> v.map { fixNumber(it) }; else -> fixNumber(v)
            }
        }

        private fun String.parseJsonMap(): Map<String, Any?> = this.let {
            @Suppress("UNCHECKED_CAST")
            MAPPER.readValue(this, Map::class.java) as Map<String, Any?>
        }

        private fun String.normalize(): String = this.replace(Regex("\\s+"), " ")
    }

}