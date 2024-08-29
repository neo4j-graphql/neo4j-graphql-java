package demo

// Simplistic GraphQL Server using SparkJava
// curl -H content-type:application/json -d'{"query": "{ movie { title, released }}"}' http://localhost:4567/graphql
// GraphiQL: https://neo4j-graphql.github.io/graphiql4all/index.html?graphqlEndpoint=http%3A%2F%2Flocalhost%3A4567%2Fgraphql&query=query%20%7B%0A%20%20movie%20%7B%20title%7D%0A%7D

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Config
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Values
import org.neo4j.graphql.*
import java.net.InetSocketAddress
import java.util.stream.Collectors


const val schema = """
type Person {
  name: ID!
  born: Int
  actedIn: [Movie] @relation(name:"ACTED_IN")
}
type Movie {
  title: ID!
  released: Int
  tagline: String
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
        AuthTokens.basic("neo4j", "test"),
        Config.builder().withoutEncryption().build()
    )

    val graphQLSchema = SchemaBuilder.buildSchema(schema, dataFetchingInterceptor = object : DataFetchingInterceptor {
        override fun fetchData(env: DataFetchingEnvironment, delegate: DataFetcher<OldCypher>): Any? {
            val (cypher, params, type, variable) = delegate.get(env)
            println(cypher)
            println(params)
            return driver.session().use { session ->
                try {
                    val result = session.run(cypher, Values.value(params))
                    when {
                        type?.isList() == true -> result.stream().map { it[variable].asObject() }
                            .collect(Collectors.toList())

                        else -> result.stream().map { it[variable].asObject() }.findFirst()
                            .orElse(emptyMap<String, Any>())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
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
                    try {
                        val queryContext =
                            QueryContext(optimizedQuery = setOf(QueryContext.OptimizationStrategy.FILTER_AS_MATCH))
                        schema.execute(
                            ExecutionInput
                                .newExecutionInput()
                                .query(query)
                                .graphQLContext(mapOf(QueryContext.KEY to queryContext))
                                .variables(params(payload))
                                .build()
                        )
                    } catch (e: OptimizedQueryException) {
                        schema.execute(
                            ExecutionInput
                                .newExecutionInput()
                                .query(query)
                                .variables(params(payload))
                                .build()
                        )
                    }
                }
                req.sendResponse(response)
            }
        }
    }
    server.start()

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

