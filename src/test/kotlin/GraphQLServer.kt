package demo

// Simplistic GraphQL Server using SparkJava

import com.google.gson.Gson
import org.neo4j.driver.v1.AuthTokens
import org.neo4j.driver.v1.GraphDatabase
import org.neo4j.driver.v1.Values
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.Translator
import spark.Request
import spark.Response
import spark.Spark
import graphql.*
import java.math.BigDecimal
import java.math.BigInteger

val schema = """
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
    fun render(value:Any) =  gson.toJson(value)
    fun query(value:String) = (gson.fromJson(value, Map::class.java)["query"] as String).also { println(it) }

    val graphQLSchema = SchemaBuilder.buildSchema(schema)
    println(graphQLSchema)
    val build = GraphQL.newGraphQL(graphQLSchema).build()
    val graphql = Translator(graphQLSchema)
    fun translate(query:String) = graphql.translate(query)

    val driver = GraphDatabase.driver("bolt://localhost",AuthTokens.basic("neo4j","test"))
    fun run(cypher:Translator.Cypher) = driver.session().use {
        println(cypher.query)
        println(cypher.params)
        try {
            // todo fix parameter mapping in translator
            val result = it.run(cypher.query, Values.value(cypher.params.mapValues {
                it.value.let {
                    when (it) {
                        is BigInteger -> it.longValueExact()
                        is BigDecimal -> it.toDouble()
                        else -> it
                    }
                }
            }))
            // result.list{ it.asMap().toList() }.flatten().groupBy({ it.first },{it.second})
            result.keys().map { key -> key to result.list().map { it.get(key).asObject() } }.toMap(LinkedHashMap())
        } catch(e:Exception) {
            e.printStackTrace()
        }
    }


    fun handler(req: Request, res: Response) = query(req.body()).let { query ->
        if (query.contains("__schema"))
            build.execute(query).let { println(render(it));it }
        else run(translate(query).first()) }

    Spark.post("/graphql","application/json", ::handler, ::render)
}

