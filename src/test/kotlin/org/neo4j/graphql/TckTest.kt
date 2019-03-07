package demo.org.neo4j.graphql

import org.codehaus.jackson.map.ObjectMapper
import org.junit.Assert
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.Translator
import java.io.File

class TckTest(val schema:String) {


    fun loadQueryPairsFrom(fileName: String): MutableList<Triple<String, String, Map<String, Any?>>> {
        val lines = File(javaClass.getResource("/$fileName").toURI())
                .readLines()
                .filterNot { it.startsWith("#") || it.isBlank() }
        var cypher: String? = null
        var graphql: String? = null
        var params: String? = null
        val testData = mutableListOf<Triple<String, String, Map<String,Any?>>>()
        // TODO possibly turn stream into type/data pairs adding to the last element in a reduce and add a new element when context changes
        for (line in lines) {
            when (line) {
                "```graphql" -> graphql = ""
                "```cypher" -> cypher = ""
                "```params" -> params = ""
                "```" ->
                    if (graphql != null && cypher != null) {
                        testData.add(Triple(graphql.trim(),cypher.trim(),params?.let { ObjectMapper().readValue(params,Map::class.java) as Map<String,Any?> } ?: emptyMap()))
                        params = null
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

    public fun testTck(fileName: String, expectedFailures: Int) {
        val pairs = loadQueryPairsFrom(fileName)
        val failed = pairs.map {
            try {
                assertQuery(schema, it.first, it.second, it.third); null
            } catch (ae: Throwable) {
                ae.message
            }
        }
                .filterNotNull()
        failed.forEach(::println)
        Assert.assertEquals("${failed.size} failed of ${pairs.size}", expectedFailures, failed.size)
    }

    companion object {
        fun assertQuery(schema:String, query: String, expected: String, params : Map<String,Any?> = emptyMap()) {
            val result = Translator(SchemaBuilder.buildSchema(schema)).translate(query).first()
            Assert.assertEquals(expected, result.query)
            Assert.assertTrue("${params} IN ${result.params}", result.params.entries.containsAll(params.entries))
        }
    }
}