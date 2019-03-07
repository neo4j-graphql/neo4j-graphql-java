package org.neo4j.graphql

import demo.org.neo4j.graphql.TckTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class CypherDirectiveTest {

    val schema = """
type Person {
    name: String @cypher(statement:"RETURN this.name")
    age(mult:Int=13) : [Int] @cypher(statement:"RETURN this.age * mult as age")
}
type Query {
    person : [Person]
}
"""

    @Test
    fun renderCypherDirective() {

        val expected = """MATCH (person:Person) RETURN person { name:apoc.cypher.runFirstColumnSingle('WITH ${"$"}this AS this  RETURN this.name',{this:person}) } AS person"""
        val query = """{ person { name }}"""
        assertQuery(query, expected, emptyMap())
    }

    @Test
    fun renderCypherDirectiveWithParamsDefaults() {

        val expected = """MATCH (person:Person) RETURN person { age:apoc.cypher.runFirstColumnMany('WITH ${"$"}this AS this ,${'$'}mult AS mult RETURN this.age * mult as age',{this:person,mult:${'$'}personMult}) } AS person"""
        val query = """{ person { age }}"""
        assertQuery(query, expected, mapOf("personMult" to 13))
    }

    @Test
    fun renderCypherDirectiveWithParams() {

        val expected = """MATCH (person:Person) RETURN person { age:apoc.cypher.runFirstColumnMany('WITH ${"$"}this AS this ,${'$'}mult AS mult RETURN this.age * mult as age',{this:person,mult:${'$'}personMult}) } AS person"""
        val query = """{ person { age(mult:25) }}"""
        assertQuery(query, expected, mapOf("personMult" to 25L))
    }

    private fun assertQuery(query: String, expected: String, params : Map<String,Any?> = emptyMap()) {
        val result = Translator(SchemaBuilder.buildSchema(schema)).translate(query).first()
        assertEquals(expected, result.query)
        assertTrue("${params} IN ${result.params}", result.params.entries.containsAll(params.entries))
    }

    @Test @Ignore
    fun testTck() {
        TckTest(schema).testTck("cypher-directive-test.md", 0)
    }
}