package org.neo4j.graphql

import demo.org.neo4j.graphql.TckTest
import org.codehaus.jackson.map.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

class PropertyTest {

    val schema = """
type Person {
    id : ID! @property(name:"an-id")
    name: String @property(name:" a name ")
}
type Query {
    person : [Person]
}
"""

    @Test
    fun renameProperty() {

        val expected = "MATCH (person:Person) WHERE person.`an-id` = \$personId AND person.` a name ` = \$personName RETURN person { name:person.` a name ` } AS person"
        val query = """{ person(id:"32",name:"Jane") { name }}"""
        assertQuery(query, expected, mapOf("personId" to "32", "personName" to "Jane"))
    }

    private fun assertQuery(query: String, expected: String, params : Map<String,Any?> = emptyMap()) {
        val result = Translator(SchemaBuilder.buildSchema(schema)).translate(query).first()
        assertEquals(expected, result.query)
        assertTrue("${params} IN ${result.params}", result.params.entries.containsAll(params.entries))
    }

    @Test @Ignore
    fun testTck() {
        TckTest(schema).testTck("property-test.md", 0)
    }
}