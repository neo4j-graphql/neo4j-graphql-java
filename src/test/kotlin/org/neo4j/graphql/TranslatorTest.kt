package org.neo4j.graphql

import org.antlr.v4.runtime.misc.ParseCancellationException
import org.junit.Test

import org.junit.Assert.*

class TranslatorTest {

    val schema =
            """type Person {
                  name: String
                  age: Int
                  livesIn : Location @relation(name:"LIVES_IN", direction:"OUT")
                  livedIn : [Location] @relation(name:"LIVED_IN", direction:"OUT")
                }
                type Location {
                   name: String
                }
                enum E { pi, e }
                    type Query {
                        person : [Person]
                        personByName(name:String) : Person
                        values(_param:String,_string:String="Joe",_int:Int=42, _float:Float=3.14, _array:[Int]=[1,2,3],_enum:E=pi, _boolean:Boolean=false,_null:String=null) : Person
                    }"""

    @Test
    fun simpleQuery() {
        val query = " { person { name age } } "
        val (cypher, _) = Translator(SchemaBuilder.buildSchema(schema)).translate(query)
        assertEquals("MATCH (person:Person) RETURN person {.name,.age}", cypher.first())
    }
    @Test
    fun multiQuery() {
        val query = " { p1: person { name } p2: person { name } } "
        val (cypher, _) = Translator(SchemaBuilder.buildSchema(schema)).translate(query)
        assertEquals(listOf("p1","p2").map{"MATCH ($it:Person) RETURN $it {.name}"}, cypher)
    }

    @Test
    fun nestedQuery() {
        val query = " { person { name age livesIn { name } } } "
        val (cypher, _) = Translator(SchemaBuilder.buildSchema(schema)).translate(query)
        assertEquals("MATCH (person:Person) RETURN person {.name,.age,livesIn:[(person)-[:LIVES_IN]->(livesInLocation) | livesInLocation {.name}][0]}", cypher.first())
    }

    @Test
    fun nestedQueryMulti() {
        val query = " { person { name age livedIn { name } } } "
        val (cypher, _) = Translator(SchemaBuilder.buildSchema(schema)).translate(query)
        assertEquals("MATCH (person:Person) RETURN person {.name,.age,livedIn:[(person)-[:LIVED_IN]->(livedInLocation) | livedInLocation {.name}]}", cypher.first())
    }

    @Test
    fun simpleQueryWhere() {
        val query = """ { person:personByName(name:"Joe") { age } } """
        val (cypher, _) = Translator(SchemaBuilder.buildSchema(schema)).translate(query)
        assertEquals("MATCH (person:Person) WHERE person.name = 'Joe' RETURN person {.age}", cypher.first())
    }
    @Test
    fun renderValues() {
        val query = """query(${"$"}_param:String) { p:values(_param:${"$"}_param) { age } } """
        val (cypher, _) = Translator(SchemaBuilder.buildSchema(schema)).translate(query)
        assertEquals("MATCH (p:Person) WHERE p._param = ${"$"}_param AND p._string = 'Joe' AND p._int = 42 AND p._float = 3.14 AND p._array = [1, 2, 3] AND p._enum = 'pi' AND p._boolean = false RETURN p {.age}", cypher.first())
    }

    @Test
    fun simpleQueryAlias() {
        val query = " { foo:person { n:name } } "
        val (cypher, _) = Translator(SchemaBuilder.buildSchema(schema)).translate(query)
        assertEquals("MATCH (foo:Person) RETURN foo {.n}", cypher.first())
    }

    @Test(expected = IllegalArgumentException::class) // todo better test
    fun unknownType() {
        val query = " { company { name } } "
        val (cypher, _) = Translator(SchemaBuilder.buildSchema(schema)).translate(query)
        assertEquals("MATCH (foo:Person) RETURN foo", cypher.first())
    }

    @Test(expected = ParseCancellationException::class)
    fun mutation() {
        val query = " { createPerson() } "
        val (cypher, _) = Translator(SchemaBuilder.buildSchema(schema)).translate(query)
        assertEquals("MATCH (foo:Person) RETURN foo", cypher.first())
    }
}