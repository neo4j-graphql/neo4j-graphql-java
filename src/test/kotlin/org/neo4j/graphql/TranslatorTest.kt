package org.neo4j.graphql

import graphql.parser.InvalidSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {

    val schema =
            """type Person {
                  name: String
                  age: Int
                  livesIn : Location @relation(name:"LIVES_IN", direction: OUT)
                  livedIn : [Location] @relation(name:"LIVED_IN", direction: OUT)
                  born : Birth
                  died : Death
                }
                type Birth @relation(name:"BORN") {
                   from: Person
                   to: Location
                   date: String
                }
                type Death @relation(name:"DIED",from:"who",to:"where") {
                   who: Person
                   where: Location
                   date: String
                }
                type Location {
                   name: String
                   founded: Person @relation(name:"FOUNDED", direction: IN)
                }
                # enum _PersonOrdering { name_asc, name_desc, age_asc, age_desc }
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
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,.age,livesIn:[(person)-[:LIVES_IN]->(personLivesIn:Location) | personLivesIn { .name }][0] } AS person")
    }

    @Test
    fun nestedQuery2ndHop() {
        val query = " { person { name age livesIn { name founded {name}} } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,.age,livesIn:[(person)-[:LIVES_IN]->(personLivesIn:Location) | personLivesIn { .name,founded:[(personLivesIn)<-[:FOUNDED]-(personLivesInFounded:Person) | personLivesInFounded { .name }][0] }][0] } AS person")
    }

    @Test
    fun richRelationship() {
        val query = " { person { name born { date to { name } } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,born:[(person)-[personBorn:BORN]->(personBornTo:Location) | personBorn { .date,to:personBornTo { .name } }][0] } AS person")
    }

    @Test
    fun richRelationshipCustomFieldNames() {
        val query = " { person { name died { date where { name } } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,died:[(person)-[personDied:DIED]->(personDiedWhere:Location) | personDied { .date,where:personDiedWhere { .name } }][0] } AS person")
    }

    @Test
    fun richRelationship2ndHop() {
        val query = " { person { name born { date to { name founded { name } } } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,born:[(person)-[personBorn:BORN]->(personBornTo:Location) | personBorn { .date,to:personBornTo { .name,founded:[(personBornTo)<-[:FOUNDED]-(personBornToFounded:Person) | personBornToFounded { .name }][0] } }][0] } AS person")
    }
    @Test
    fun richRelationship3rdHop() {
        val query = " { person { name born { date to { name founded { name born { date to { name } } } } } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,born:[(person)-[personBorn:BORN]->(personBornTo:Location) | personBorn { .date,to:personBornTo { .name,founded:[(personBornTo)<-[:FOUNDED]-(personBornToFounded:Person) | personBornToFounded { .name,born:[(personBornToFounded)-[personBornToFoundedBorn:BORN]->(personBornToFoundedBornTo:Location) | personBornToFoundedBorn { .date,to:personBornToFoundedBornTo { .name } }][0] }][0] } }][0] } AS person")
    }

    @Test
    fun nestedQueryParameter() {
        val query = """ { person { name age livesIn(name:"Berlin") { name } } } """
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,.age,livesIn:[(person)-[:LIVES_IN]->(personLivesIn:Location) WHERE personLivesIn.name = \$personLivesInName | personLivesIn { .name }][0] } AS person",
                mapOf("personLivesInName" to "Berlin"))
    }

    @Test
    fun nestedQueryMulti() {
        val query = " { person { name age livedIn { name } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,.age,livedIn:[(person)-[:LIVED_IN]->(personLivedIn:Location) | personLivedIn { .name }] } AS person")
    }

    @Test
    fun nestedQuerySliceOffset() {
        val query = " { person { livedIn(offset:3) { name } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { livedIn:[(person)-[:LIVED_IN]->(personLivedIn:Location) | personLivedIn { .name }][3..] } AS person")
    }
    @Test
    fun nestedQuerySliceFirstOffset() {
        val query = " { person { livedIn(first:2,offset:3) { name } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { livedIn:[(person)-[:LIVED_IN]->(personLivedIn:Location) | personLivedIn { .name }][3..5] } AS person")
    }

    @Test
    fun nestedQuerySliceFirst() {
        val query = " { person { livedIn(first:2) { name } } } "
        assertQuery(query, "MATCH (person:Person) RETURN person { livedIn:[(person)-[:LIVED_IN]->(personLivedIn:Location) | personLivedIn { .name }][0..2] } AS person")
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
    fun relationWithSameTypes() {
        val schema = """
            type User {
              name:String
              referredBy: Referral @relation(direction: OUT)
              referred:[Referral] @relation(direction: IN)
            }
            type Referral @relation (name:"REFERRED_BY", from:"user", to: "referredBy" ) {
              user:User
              referredBy:User
              referralDate:String
            }
            """
        val query = """ {user(name:"Jane") {
            name
            referredBy { referralDate referredBy {name} }
            referred { referralDate user {name} }
            } }"""
        assertQuery(query, "MATCH (user:User) WHERE user.name = \$userName RETURN user { .name," +
                "referredBy:[(user)-[userReferredBy:REFERRED_BY]->(userReferredByReferredBy:User) | userReferredBy { .referralDate,referredBy:userReferredByReferredBy { .name } }][0]," +
                "referred:[(user)<-[userReferred:REFERRED_BY]-(userReferredReferredBy:User) | userReferred { .referralDate,user:userReferredReferredBy { .name } }] } AS user",
                mapOf("userName" to "Jane"), schema)
    }

    @Test
    fun renderValues() {
        val query = "query(\$_param:String) { p:values(_param:\$_param) { age } }"
        //in new graphql args seem to be ordered alphabetically on schema creation
        assertQuery(query, "MATCH (p:Person) WHERE p._param = \$_param " +
                "AND p._array = \$p_array " +
                "AND p._boolean = \$p_boolean " +
                "AND p._enum = \$p_enum " +
                "AND p._float = \$p_float " +
                "AND p._int = \$p_int " +
                "AND p._string = \$p_string " +
                "RETURN p { .age } AS p",
                mapOf("p_string" to "Joe","p_int" to 42, "p_float" to 3.14, "p_array" to listOf(1,2,3), "p_enum" to "pi","p_boolean" to false))
    }

    @Test
    fun simpleQueryAlias() {
        val query = " { foo:person { n:name } } "
        assertQuery(query, "MATCH (foo:Person) RETURN foo { n:foo.name } AS foo")
    }

    @Test
    fun namedFragment() {
        val query = " query { person { ...name } } fragment name on Person { name } "
        assertQuery(query, "MATCH (person:Person) RETURN person { .name } AS person")
    }

    @Test
    fun namedFragmentMultiField() {
        val query = "  fragment details on Person { name, age } query { person { ...details } }"
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,.age } AS person")
    }
    @Test
    fun inlineFragment() {
        val query = " query { person { ... on Person { name } } }"
        assertQuery(query, "MATCH (person:Person) RETURN person { .name } AS person")
    }

    @Test
    fun inlineFragmentMultiFields() {
        val query = " query { person { ... on Person { name,age } } }"
        assertQuery(query, "MATCH (person:Person) RETURN person { .name,.age } AS person")
    }

    private fun assertQuery(query: String, expected: String, params: Map<String, Any> = emptyMap(), schema: String = this.schema) {
        val result = Translator(SchemaBuilder.buildSchema(schema)).translate(query).first()
        assertEquals(expected, result.query)
        assertTrue("${params} IN ${result.params}",result.params.entries.containsAll(params.entries))
    }

    @Test(expected = IllegalArgumentException::class) // todo better test
    fun unknownType() {
        val query = " { company { name } } "
        Translator(SchemaBuilder.buildSchema(schema)).translate(query)
    }

    @Test(expected = InvalidSyntaxException::class)
    fun mutation() {
        val query = " { createPerson() } "
        Translator(SchemaBuilder.buildSchema(schema)).translate(query)
    }
}