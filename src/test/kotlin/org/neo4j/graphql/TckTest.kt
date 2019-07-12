package demo.org.neo4j.graphql

import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.codehaus.jackson.map.ObjectMapper
import org.junit.Assert
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.Translator
import java.io.File

class TckTest(val schema: String) {

    fun loadQueryPairsFrom(fileName: String): MutableList<Triple<String, String, Map<String, Any?>>> {
        val lines = File(javaClass.getResource("/$fileName").toURI())
            .readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
        var cypher: String? = null
        var graphql: String? = null
        var params: String? = null
        val testData = mutableListOf<Triple<String, String, Map<String, Any?>>>()
        // TODO possibly turn stream into type/data pairs adding to the last element in a reduce and add a new element when context changes
        for (line in lines) {
            when (line) {
                "```graphql" -> graphql = ""
                "```cypher" -> cypher = ""
                "```params" -> params = ""
                "```" ->
                    if (graphql != null && cypher != null) {
                        testData.add(Triple(graphql.trim(), cypher.trim(), params?.let { MAPPER.readValue(params, Map::class.java) as Map<String, Any?> }
                                ?: emptyMap()))
                        graphql = null
                        cypher = null
                        params = null
                    }
                else ->
                    if (cypher != null) cypher += " " + line.trim()
                    else if (params != null) params += line.trim()
                    else if (graphql != null) graphql += " " + line.trim()
            }
            //            println("line '$line' GQL '$graphql' Cypher '$cypher'")
        }
        return testData
    }

    public fun testTck(fileName: String, expectedFailures: Int, fail: Boolean = false) {
        val pairs = loadQueryPairsFrom(fileName)
        val failed = pairs.map {
            try {
                assertQuery(schema, it.first, it.second, it.third); null
            } catch (ae: Throwable) {
                if (fail) when (ae) {
                    is ParseCancellationException -> throw RuntimeException((ae.cause!! as RecognitionException).let { "expected: ${it.expectedTokens} offending ${it.offendingToken}" })
                    else -> throw ae
                }
                else ae.message ?: ae.toString()
            }
        }
            .filterNotNull()
        failed.forEach(::println)
        println("""Succeeded in "$fileName": ${pairs.size - failed.size} of ${pairs.size}""")
        Assert.assertEquals("${failed.size} failed of ${pairs.size}", expectedFailures, failed.size)
    }

    companion object {
        val MAPPER = ObjectMapper()

        fun assertQuery(schema: String, query: String, expected: String, params: Map<String, Any?> = emptyMap()) {
            val result = Translator(SchemaBuilder.buildSchema(schema)).translate(query).first()
            println(result.query)
            Assert.assertEquals(expected.replace(Regex("\\s+"), " "), result.query)
            Assert.assertTrue("${params} IN ${result.params}", fixNumbers(result.params).entries.containsAll(fixNumbers(params).entries))
        }

        private fun fixNumber(v: Any?): Any? = when (v) {
            is Float -> v.toDouble(); is Int -> v.toLong(); else -> v
        }

        private fun fixNumbers(params: Map<String, Any?>) = params.mapValues { (_, v) ->
            when (v) {
                is List<*> -> v.map { fixNumber(it) }; else -> fixNumber(v)
            }
        }
    }
}