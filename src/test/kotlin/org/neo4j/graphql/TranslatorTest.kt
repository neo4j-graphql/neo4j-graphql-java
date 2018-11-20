package org.neo4j.graphql

import org.antlr.v4.runtime.misc.ParseCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {

    val schema =
            """type Person {
                  name: String
                  age: Int
                  livesIn : Location @relation(name:"LIVES_IN", direction:"OUT")
                  livedIn : [Location] @relation(name:"LIVED_IN", direction:"OUT")
                  born : Birth
                }
                type Birth @relation(name:"BORN") {
                   start: Person
                   end: Location
                   date: String
                }
                type Location {
                   name: String
                   founded: Person @relation(name:"FOUNDED", direction:"IN")
                }
                enum _PersonOrdering { name_asc, name_desc, age_asc, age_desc }
                enum E { pi, e }
                    type Query {
                        person : [Person]
                        personByName(name:String) : Person
                        values(_param:String,_string:String="Joe",_int:Int=42, _float:Float=3.14, _array:[Int]=[1,2,3],_enum:E=pi, _boolean:Boolean=false,_null:String=null) : Person
                     }
                """



    @Test
    fun simpleQuery() {
        val query = " { person { name age } } "
        val expected = "MATCH (person:Person) RETURN person { .name,.age } AS person"
        assertQuery(query, expected)
    }
    @Test
    fun multiQuery() {
        val query = " { p1: person { name } p2: person { name } } "
        val queries = Translator(SchemaBuilder.buildSchema(schema)).translate(query).map { it.query }
        assertEquals(listOf("p1","p2").map{"MATCH ($it:Person) RETURN $it { .name } AS $it"}, queries)
    }

    @Test
    fun nestedQuery() {
        val query = " { person { name age livesIn { name } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,.age,livesIn:[(person)-[:LIVES_IN]->(livesIn:Location) | livesIn { .name }][0] } AS person")
    }

    @Test
    fun nestedQuery2ndHop() {
        val query = " { person { name age livesIn { name founded {name}} } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,.age,livesIn:[(person)-[:LIVES_IN]->(livesIn:Location) | livesIn { .name,founded:[(livesIn)<-[:FOUNDED]-(founded:Person) | founded { .name }][0] }][0] } AS person")
    }

    @Test
    fun richRelationship() {
        val query = " { person { name born { date end { name } } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,born:[(person)-[born:BORN]->(bornEnd:Location) | born { .date,end:bornEnd { .name } }][0] } AS person")
    }

    @Test
    fun nestedQueryParameter() {
        val query = """ { person { name age livesIn(name:"Berlin") { name } } } """
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,.age,livesIn:[(person)-[:LIVES_IN]->(livesIn:Location) WHERE livesIn.name = \$livesInName | livesIn { .name }][0] } AS person",
                mapOf("livesInName" to "Berlin"))
    }

    @Test
    fun nestedQueryMulti() {
        val query = " { person { name age livedIn { name } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,.age,livedIn:[(person)-[:LIVED_IN]->(livedIn:Location) | livedIn { .name }] } AS person")
    }

    @Test
    fun nestedQuerySliceOffset() {
        val query = " { person { livedIn(offset:3) { name } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { livedIn:[(person)-[:LIVED_IN]->(livedIn:Location) | livedIn { .name }][3..] } AS person")
    }
    @Test
    fun nestedQuerySliceFirstOffset() {
        val query = " { person { livedIn(first:2,offset:3) { name } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { livedIn:[(person)-[:LIVED_IN]->(livedIn:Location) | livedIn { .name }][3..5] } AS person")
    }

    @Test
    fun nestedQuerySliceFirst() {
        val query = " { person { livedIn(first:2) { name } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { livedIn:[(person)-[:LIVED_IN]->(livedIn:Location) | livedIn { .name }][0..2] } AS person")
    }

    @Test
    fun simpleQueryWhere() {
        val query = """ { person:personByName(name:"Joe") { age } } """
        assertQuery(query, "MATCH (person:Person) WHERE person.name = \$personName RETURN person { .age } AS person", mapOf("personName" to "Joe"))
    }

    @Test
    fun simpleQueryFirstOffset() {
        val query = """ { person:person(first:2,offset:3) { age } } """
        assertQuery(query, "MATCH (person:Person) RETURN person { .age } AS person SKIP 3 LIMIT 2")
    }

    @Test
    fun orderByQuerySingle() {
        val query = """ { person:person(orderBy:[name_asc]) { age } } """
        assertQuery(query, "MATCH (person:Person) RETURN person { .age } AS person ORDER BY person.name ASC")
    }

    @Test
    fun orderByQueryTwo() {
        val query = """ { person:person(orderBy:[age_desc, name_asc]) { age } } """
        assertQuery(query, "MATCH (person:Person) RETURN person { .age } AS person ORDER BY person.age DESC, person.name ASC")
    }

    @Test
    fun simpleQueryFirst() {
        val query = """ { person:person(first:2) { age } } """
        assertQuery(query, "MATCH (person:Person) RETURN person { .age } AS person LIMIT 2")
    }

    @Test
    fun simpleQueryOffset() {
        val query = """ { person:person(offset:3) { age } } """
        assertQuery(query, "MATCH (person:Person) RETURN person { .age } AS person SKIP 3")
    }

    @Test
    fun renderValues() {
        val query = "query(\$_param:String) { p:values(_param:\$_param) { age } }"
        assertQuery(query, "MATCH (p:Person) WHERE p._param = \$_param AND p._string = \$p_string AND p._int = \$p_int AND p._float = \$p_float AND p._array = \$p_array AND p._enum = \$p_enum AND p._boolean = \$p_boolean RETURN p { .age } AS p",
                mapOf("p_string" to "Joe","p_int" to 42, "p_float" to 3.14, "p_array" to listOf(1,2,3), "p_enum" to "pi","p_boolean" to false))
    }

    @Test
    fun simpleQueryAlias() {
        val query = " { foo:person { n:name } } "
        assertQuery(query, "MATCH (foo:Person) RETURN foo { .n } AS foo")
    }

    @Test
    fun namedFragment() {
        val query = " query { person { ...name } } fragment name on Person { name } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name } AS person")
    }
    @Test
    fun inlineFragment() {
        val query = " query { person { ... on Person { name } } }"
        assertQuery(query, "MATCH (person:Person) RETURN person { .name } AS person")
    }

    private fun assertQuery(query: String, expected: String, params : Map<String,Any> = emptyMap()) {
        val result = Translator(SchemaBuilder.buildSchema(schema)).translate(query).first()
        assertEquals(expected, result.query)
        assertTrue("${params} IN ${result.params}",result.params.entries.containsAll(params.entries))
    }

    @Test(expected = IllegalArgumentException::class) // todo better test
    fun unknownType() {
        val query = " { company { name } } "
        Translator(SchemaBuilder.buildSchema(schema)).translate(query)
    }

    @Test(expected = ParseCancellationException::class)
    fun mutation() {
        val query = " { createPerson() } "
        Translator(SchemaBuilder.buildSchema(schema)).translate(query)
    }
}