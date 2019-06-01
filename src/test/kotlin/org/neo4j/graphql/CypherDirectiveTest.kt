package org.neo4j.graphql

import demo.org.neo4j.graphql.TckTest
import graphql.language.Node
import graphql.language.VariableReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class CypherDirectiveTest {

    val schema = """
type Person {
    id: ID
    name: String @cypher(statement:"RETURN this.name")
    age(mult:Int=13) : [Int] @cypher(statement:"RETURN this.age * mult as age")
    friends: [Person] @cypher(statement:""${'"'}
    MATCH (this)-[:KNOWS]-(o)
    RETURN o
    ""${'"'})
}
type Query {
    person : [Person]
    p2: [Person] @cypher(statement:"MATCH (p:Person) RETURN p")
    p3(name:String): Person @cypher(statement:"MATCH (p:Person) WHERE p.name = name RETURN p LIMIT 1")
}
type Mutation {
    createPerson(name:String): Person @cypher(statement:"CREATE (p:Person) SET p.name = name RETURN p")
}
schema {
 query: Query
 mutation: Mutation
}
"""

    @Test
    fun renderCypherFieldDirectiveScalar() {

        val expected = """MATCH (person:Person) RETURN person { name:apoc.cypher.runFirstColumnSingle('WITH ${"$"}this AS this RETURN this.name',{this:person}) } AS person"""
        val query = """{ person { name }}"""
        assertQuery(query, expected, emptyMap())
    }

    @Test
    fun renderCypherFieldDirectiveNested() {
        val expected = """MATCH (person:Person) RETURN person { friends:[personFriends IN apoc.cypher.runFirstColumnMany('WITH ${"$"}this AS this  MATCH (this)-[:KNOWS]-(o)
            | RETURN o
            | ',{this:person}) | personFriends { .id }] } AS person""".trimMargin()
        val query = """{ person { friends { id } }}"""
        assertQuery(query, expected, emptyMap())
    }

    @Test
    fun renderCypherFieldDirectiveWithParamsDefaults() {
        val expected = """MATCH (person:Person) RETURN person { age:apoc.cypher.runFirstColumnMany('WITH ${"$"}this AS this,${'$'}mult AS mult RETURN this.age * mult as age',{this:person,mult:${'$'}personMult}) } AS person"""
        val query = """{ person { age }}"""
        assertQuery(query, expected, mapOf("personMult" to 13))
    }

    @Test
    fun renderCypherFieldDirectiveWithParams() {

        val expected = """MATCH (person:Person) RETURN person { age:apoc.cypher.runFirstColumnMany('WITH ${"$"}this AS this,${'$'}mult AS mult RETURN this.age * mult as age',{this:person,mult:${'$'}personMult}) } AS person"""
        val query = """{ person { age(mult:25) }}"""
        assertQuery(query, expected, mapOf("personMult" to 25L))
    }

    @Test
    fun renderCypherQueryDirective() {
        val expected = """UNWIND apoc.cypher.runFirstColumnMany('MATCH (p:Person) RETURN p',{}) AS p2 RETURN p2 { .id } AS p2"""
        val query = """{ p2 { id }}"""
        assertQuery(query, expected, emptyMap())
    }
    @Test
    fun renderCypherQueryDirectiveParams() {
        val expected = """UNWIND apoc.cypher.runFirstColumnSingle('WITH ${'$'}name AS name MATCH (p:Person) WHERE p.name = name RETURN p LIMIT 1',{name:${'$'}p3Name}) AS p3 RETURN p3 { .id } AS p3"""
        val query = """{ p3(name:"Jane") { id }}"""
        assertQuery(query, expected, mapOf("p3Name" to "Jane"))
    }
    @Test
    fun renderCypherQueryDirectiveParamsArgs() {
        val expected = """UNWIND apoc.cypher.runFirstColumnSingle('WITH ${'$'}name AS name MATCH (p:Person) WHERE p.name = name RETURN p LIMIT 1',{name:${'$'}pname}) AS p3 RETURN p3 { .id } AS p3"""
        val query = """query(${'$'}pname:String) { p3(name:${'$'}pname) { id }}"""
        assertQuery(query, expected, mapOf("pname" to "foo"),mapOf("pname" to "foo"))
    }

    @Test
    fun renderCypherMutationDirective() {
        val expected = """CALL apoc.cypher.doIt('WITH ${'$'}name AS name CREATE (p:Person) SET p.name = name RETURN p',{name:${'$'}personName}) YIELD value WITH value[head(keys(value))] AS person RETURN person { .id } AS person"""
        val query = """mutation { person: createPerson(name:"Joe") { id }}"""
        assertQuery(query, expected, mapOf("personName" to "Joe"))
    }


    @Test
    fun testTck() {
        TckTest(schema).testTck("cypher-directive-test.md", 0)
    }

    private fun assertQuery(query: String, expected: String, params : Map<String,Any?> = emptyMap(),queryParams : Map<String,Any?> = emptyMap()) {
        val result = Translator(SchemaBuilder.buildSchema(schema)).translate(query, queryParams).first()
        assertEquals(expected, result.query)
        assertTrue("${params} IN ${result.params}", params.all { val v=result.params[it.key]; when (v) { is Node<*> -> v.isEqualTo(it.value as Node<*>) else -> v == it.value}})
    }
}