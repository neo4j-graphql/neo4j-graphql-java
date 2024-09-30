package demo

import graphql.GraphQL
import org.intellij.lang.annotations.Language
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.driver.adapter.Neo4jAdapter
import org.neo4j.graphql.driver.adapter.Neo4jDriverAdapter


fun main() {
    val driver: Driver = GraphDatabase.driver(
        "bolt://localhost",
        AuthTokens.basic("neo4j", "test")
    )
    val neo4jAdapter = Neo4jDriverAdapter(driver, Neo4jAdapter.Dialect.NEO4J_5)

    @Language("GraphQL") val sdl = """
        type Movie {
          movieId: ID!
          title: String
        }
    """.trimIndent()

    val schema = SchemaBuilder.buildSchema(sdl, neo4jAdapter = neo4jAdapter)
    val graphql = GraphQL.newGraphQL(schema).build()
    val movies = graphql.execute("{ movies { title }}")
    println(movies.toSpecification())
}
