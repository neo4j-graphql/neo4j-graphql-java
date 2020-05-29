// Simplistic GraphQL Server using SparkJava
@Grapes([
  @Grab('com.sparkjava:spark-core:2.7.2'),
  @Grab('org.neo4j.driver:neo4j-java-driver:1.7.2'),
  @Grab('org.neo4j:neo4j-graphql-java:1.0.0'),
  @Grab('com.google.code.gson:gson:2.8.5')
])

import spark.*
import static spark.Spark.*
import com.google.gson.Gson
import org.neo4j.graphql.*
import org.neo4j.driver.v1.*

schema = """
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
type Query {
    person : [Person]
}
"""

gson = new Gson()
render = (ResponseTransformer)gson.&toJson
def query(value) { gson.fromJson(value,Map.class)["query"] }

graphql = new Translator(SchemaBuilder.buildSchema(schema))
def translate(query) { graphql.translate(query) }

driver = GraphDatabase.driver("bolt://localhost",AuthTokens.basic("neo4j","password"))
def run(cypher) { driver.session().withCloseable { it.run(cypher.query, Values.value(cypher.params)).list{ it.asMap() }}}

post("/graphql","application/json", { req, res ->  run(translate(query(req.body())).first()) }, render);
