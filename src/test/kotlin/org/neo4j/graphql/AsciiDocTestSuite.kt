package org.neo4j.graphql

import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.codehaus.jackson.map.ObjectMapper
import org.junit.Assert
import java.io.File

class AsciiDocTestSuite(private val fileName: String) {

    class TestRun(val request: String,
            val cypher: String,
            val cypherParams: Map<String, Any?> = emptyMap(),
            val requestParams: Map<String, Any?> = emptyMap())

    val schema: String
    private val tests: List<TestRun>

    init {
        this.tests = mutableListOf()
        val lines = File(javaClass.getResource("/$fileName").toURI())
            .readLines()
            .filterNot { it.startsWith("#") || it.isBlank() || it.startsWith("//") }
        var cypher: String? = null
        var graphql: String? = null
        var requestParams: String? = null
        var cypherParams: String? = null
        var schema: String? = null
        var inside = false
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
                                graphql.trim(),
                                cypher.trim(),
                                cypherParams?.parseJsonMap() ?: emptyMap(),
                                requestParams?.parseJsonMap() ?: emptyMap()))
                        graphql = null
                        cypher = null
                        cypherParams = null
                        requestParams = null
                    }
                    inside = !inside
                }
                else -> {
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

    fun runSuite(expectedFailures: Int = 0, fail: Boolean = false) {
        val failed = tests.mapNotNull { it ->
            try {
                runTest(it); null
            } catch (ae: Throwable) {
                if (fail) when (ae) {
                    is ParseCancellationException -> throw RuntimeException((ae.cause!! as RecognitionException).let { "expected: ${it.expectedTokens} offending ${it.offendingToken}" })
                    else -> throw ae
                }
                else ae.message ?: ae.toString()
            }
        }
        failed.forEach(::println)
        println("""Succeeded in "$fileName": ${tests.size - failed.size} of ${tests.size}""")
        Assert.assertEquals("${failed.size} failed of ${tests.size}", expectedFailures, failed.size)
    }

    fun runTest(test: TestRun) {
        runTest(test.request, test.cypher, test.cypherParams, test.requestParams)
    }

    @Suppress("SameParameterValue")
    fun runTest(graphQLQuery: String,
            expectedCypherQuery: String,
            cypherParams: Map<String, Any?> = emptyMap(),
            requestParams: Map<String, Any?> = emptyMap()) {
        val result = translate(graphQLQuery, requestParams)
        println(result.query)
        Assert.assertEquals(expectedCypherQuery.replace(Regex("\\s+"), " "), result.query)
        Assert.assertTrue("${cypherParams} IN ${result.params}", fixNumbers(result.params).entries.containsAll(fixNumbers(cypherParams).entries))
    }

    fun translate(query: String, requestParams: Map<String, Any?> = emptyMap()): Translator.Cypher {
        return Translator(SchemaBuilder.buildSchema(schema)).translate(query, requestParams).first()
    }

    companion object {
        val MAPPER = ObjectMapper()

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
    }

}