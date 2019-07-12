package demo.org.neo4j.graphql

import org.junit.Assert.assertEquals
import org.junit.Test
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.Translator
import java.io.InputStreamReader


class MovieSchemaTest {

    val schema = InputStreamReader(javaClass.getResourceAsStream("/movies-test-schema.graphql")).readText()

    @Suppress("SameParameterValue")
    private fun testTranslation(graphQLQuery: String, expectedCypherQuery: String, params: Map<String, Any> = emptyMap()) {
        val query = Translator(SchemaBuilder.buildSchema(schema)).translate(graphQLQuery, emptyMap(), context = Translator.Context(topLevelWhere = false)).first()
        assertEquals(expectedCypherQuery.normalizeWhitespace(), query.query.normalizeWhitespace())
        assertEquals(params, query.params)
    }

    private fun String.normalizeWhitespace() = this.replace("\\s+".toRegex(), " ")

    @Test
    fun `testsimple  Cypher  query`() {
        val graphQLQuery = """{  Movie(title: "River Runs Through It, A")  {  title }  }"""
        val expectedCypherQuery = """MATCH  (movie:Movie {title:${"$"}movieTitle})  RETURN  movie  {  .title  } AS  movie"""

        testTranslation(graphQLQuery, expectedCypherQuery, mapOf("movieTitle" to "River Runs Through It, A"))
    }

    @Test
    fun testOrderBy() {
        val graphQLQuery = """{  Movie(orderBy:year_desc, first:10)  {  title }  }"""
        val expectedCypherQuery = """MATCH  (movie:Movie) RETURN  movie  {  .title  } AS  movie ORDER BY movie.year DESC LIMIT 10"""

        val query = Translator(SchemaBuilder.buildSchema(schema)).translate(graphQLQuery).first()
        assertEquals(expectedCypherQuery.normalizeWhitespace(), query.query.normalizeWhitespace())
    }

    @Test
    fun testTck() {
        TckTest(schema).testTck("movie-test.md", 0, true)
    }
}