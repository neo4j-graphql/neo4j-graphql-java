package demo

import graphql.GraphQL
import graphql.language.VariableReference
import graphql.schema.*
import org.intellij.lang.annotations.Language
import org.neo4j.driver.v1.AuthTokens
import org.neo4j.driver.v1.GraphDatabase
import org.neo4j.graphql.Cypher
import org.neo4j.graphql.DataFetchingInterceptor
import org.neo4j.graphql.SchemaBuilder
import java.math.BigDecimal
import java.math.BigInteger


fun initBoundSchema(schema: String): GraphQLSchema {
    val driver = GraphDatabase.driver("bolt://localhost", AuthTokens.basic("neo4j", "test"))

    val dataFetchingInterceptor = object : DataFetchingInterceptor {
        override fun fetchData(env: DataFetchingEnvironment, delegate: DataFetcher<Cypher>): Any? {
            val cypher = delegate.get(env)
            return driver.session().use { session ->
                val result = session.run(cypher.query, cypher.params.mapValues { toBoltValue(it.value, env.variables) })
                val key = result.keys().stream().findFirst().orElse(null)
                if (isListType(cypher.type)) {
                    result.list().map { record -> record.get(key).asObject() }

                } else {
                    result.list().map { record -> record.get(key).asObject() }
                        .firstOrNull() ?: emptyMap<String, Any>()
                }
            }
        }
    }
    return SchemaBuilder.buildSchema(schema, dataFetchingInterceptor = dataFetchingInterceptor)
}

fun main() {
    @Language("GraphQL") val schema = initBoundSchema("""
        type Movie {
          movieId: ID!
          title: String
        }
    """.trimIndent())
    val graphql = GraphQL.newGraphQL(schema).build()
    val movies = graphql.execute("{ movie { title }}")
}

fun toBoltValue(value: Any?, params: Map<String, Any?>) = when (value) {
    is VariableReference -> params[value.name]
    is BigInteger -> value.longValueExact()
    is BigDecimal -> value.toDouble()
    else -> value
}

private fun isListType(type: GraphQLType?): Boolean {
    return when (type) {
        is GraphQLType -> true
        is GraphQLNonNull -> isListType(type.wrappedType)
        else -> false
    }
}