package demo

// Simplistic GraphQL Server using SparkJava

import com.google.gson.Gson
import graphql.GraphQL
import org.neo4j.driver.v1.AuthTokens
import org.neo4j.driver.v1.GraphDatabase
import org.neo4j.driver.v1.Values
import org.neo4j.graphql.Cypher
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.Translator
import org.neo4j.graphql.isList
import spark.Request
import spark.Response
import spark.Spark

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

fun main() {
    val gson = Gson()
    fun render(value: Any) = gson.toJson(value)
    fun parseBody(value: String) = gson.fromJson(value, Map::class.java)

    fun query(payload: Map<*, *>) = (payload["query"]!! as String).also { println(it) }
    fun params(payload: Map<*, *>): Map<String, Any?> = payload["variables"]
        .let {
            @Suppress("UNCHECKED_CAST")
            when (it) {
                is String -> if (it.isBlank()) emptyMap<String, Any?>() else gson.fromJson(it, Map::class.java)
                is Map<*, *> -> it
                else -> emptyMap<String, Any?>()
            } as Map<String, Any?>
        }.also { println(it) }


    val graphQLSchema = SchemaBuilder.buildSchema(schema)
    println(graphQLSchema)
    val schema = GraphQL.newGraphQL(graphQLSchema).build()
    val translator = Translator(graphQLSchema)
    fun translate(query: String, params: Map<String, Any?>) = translator.translate(query, params)

    val driver = GraphDatabase.driver("bolt://localhost", AuthTokens.basic("neo4j", "test"))
    fun run(cypher: Cypher) = driver.session().use {
        println(cypher.query)
        println(cypher.params)
        try {
            // todo fix parameter mapping in translator
            val result = it.run(cypher.query, Values.value(cypher.params))
            if (cypher.type?.isList() == true) {
                result.keys().map { key -> key to result.list().map { row -> row.get(key).asObject() } }.toMap(LinkedHashMap())
            } else {
                result.keys().map { key -> key to result.list().map { row -> row.get(key).asObject() }.firstOrNull() }.toMap(LinkedHashMap())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    fun handler(req: Request, @Suppress("UNUSED_PARAMETER") res: Response) = req.body().let { body ->
        val payload = parseBody(body)
        val query = query(payload)
        if (query.contains("__schema"))
            schema.execute(query).let { println(render(it));it }
        else run(translate(query, params(payload)).first())
    }

    Spark.post("/graphql", "application/json", ::handler, ::render)
}

