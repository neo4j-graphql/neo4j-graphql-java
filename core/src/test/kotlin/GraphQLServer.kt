package demo

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import graphql.ExecutionInput
import graphql.GraphQL
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Config
import org.neo4j.driver.GraphDatabase
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.driver.adapter.Neo4jAdapter
import org.neo4j.graphql.driver.adapter.Neo4jDriverAdapter
import java.net.InetSocketAddress


const val schema = """
type Role @relationshipProperties {
   roles: [String]
}
type Person {
  name: String
  born: Int
  movies: [Movie] @relationship(type:"ACTED_IN", direction: OUT, properties: Role)
}
type Movie {
  title: String
  released: Int
  characters: [Person] @relationship(type:"ACTED_IN", direction: IN, properties: Role)
}
"""

val mapper = ObjectMapper()

fun main() {

    fun query(payload: Map<*, *>) = (payload["query"]!! as String).also { println(it) }
    fun params(payload: Map<*, *>): Map<String, Any?> = payload["variables"]
        .let {
            @Suppress("UNCHECKED_CAST")
            when (it) {
                is String -> if (it.isBlank()) emptyMap<String, Any?>() else mapper.readValue(it, Map::class.java)
                is Map<*, *> -> it
                else -> emptyMap<String, Any?>()
            } as Map<String, Any?>
        }.also { println(it) }

    val driver = GraphDatabase.driver(
        "bolt://localhost",
        AuthTokens.basic("neo4j", "test1234"),
        Config.builder().withoutEncryption().build()
    )
    val neo4jAdapter = Neo4jDriverAdapter(driver, Neo4jAdapter.Dialect.NEO4J_5)
    val graphQLSchema = SchemaBuilder.buildSchema(schema, neo4jAdapter = object : Neo4jAdapter {
        override fun getDialect() = neo4jAdapter.getDialect()
        override fun executeQuery(cypher: String, params: Map<String, *>): List<Map<String, *>> {
            println(cypher)
            println(params)
            return neo4jAdapter.executeQuery(cypher, params)
        }
    })

    val schema = GraphQL.newGraphQL(graphQLSchema).build()

    val server: HttpServer = HttpServer.create(InetSocketAddress(4567), 0)

    server.createContext("/graphql") { req ->
        when {
            req.requestMethod == "OPTIONS" -> req.sendResponse(null)
            req.requestMethod == "POST" && req.requestHeaders["Content-Type"]?.contains("application/json") == true -> {
                val payload = mapper.readValue(req.requestBody, Map::class.java)
                val query = query(payload)
                val response = if (query.contains("__schema")) {
                    schema.execute(query).let { println(mapper.writeValueAsString(it));it }
                } else {
                    val queryContext = QueryContext()
                    schema.execute(
                        ExecutionInput
                            .newExecutionInput()
                            .query(query)
                            .graphQLContext(mapOf(QueryContext.KEY to queryContext))
                            .variables(params(payload))
                            .build()
                    )
                }
                req.sendResponse(response)
            }
        }
    }
    server.start()

    println("Api can be accessed via http://localhost:${server.address.port}/graphql")

}

private fun HttpExchange.sendResponse(data: Any?) {
    val responseString = data?.let { mapper.writeValueAsString(it) }
    //  CORS
    this.responseHeaders.add("Access-Control-Allow-Origin", "*")
    this.responseHeaders.add("Access-Control-Allow-Headers", "*")
    this.responseHeaders.add("Content-Type", "application/json")
    this.sendResponseHeaders(200, responseString?.length?.toLong() ?: 0)
    if (responseString != null) this.responseBody.use { it.write(responseString.toByteArray()) }
}

