package org.neo4j.graphql

import org.codehaus.jackson.map.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilterTest {

    val schema = """
enum Gender { female, male }
type Person {
    id : ID!
    name: String
    age: Int
    height: Float
    fun: Boolean
    gender: Gender
    company: Company @relation(name:"WORKS_AT")
}
type Company {
    name: String
    employees: [Person] @relation(name:"WORKS_AT", direction: IN)
}
type Query {
    person : [Person]
}
"""

    @Test
    fun simpleFilter() {

        val expected = "MATCH (person:Person) WHERE person.gender = \$filterPersonGender RETURN person { .name } AS person"
        val query = "{ person(filter: { gender: male }) { name }}"
        assertQuery(query, expected, mapOf("filterPersonGender" to "male"))
    }

    private fun assertQuery(query: String, expected: String, params : Map<String,Any?> = emptyMap()) {
        val result = Translator(SchemaBuilder.buildSchema(schema)).translate(query).first()
        assertEquals(expected, result.query)
        assertTrue("${params} IN ${result.params}", result.params.entries.containsAll(params.entries))
    }

    @Test
    fun testTck() {
        val pairs = loadQueryPairsFrom("filter-test.md")
        val failed = pairs.map { try { assertQuery(it.first,it.second, it.third); null } catch(ae:Throwable) { ae.message } }
              .filterNotNull()
        failed.forEach(::println)
        val EXPECTED_FAILURES = 59
        assertEquals("${failed.size} failed of ${pairs.size}", EXPECTED_FAILURES, failed.size)
    }

    private fun loadQueryPairsFrom(fileName: String): MutableList<Triple<String, String, Map<String, Any?>>> {
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
}