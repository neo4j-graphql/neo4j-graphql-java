package org.neo4j.graphql

import org.antlr.v4.runtime.misc.ParseCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

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

    private fun assertQuery(query: String, expected: String, params : Map<String,Any> = emptyMap()) {
        val result = Translator(SchemaBuilder.buildSchema(schema)).translate(query).first()
        assertEquals(expected, result.query)
        assertTrue("${params} IN ${result.params}",result.params.entries.containsAll(params.entries))
    }
}