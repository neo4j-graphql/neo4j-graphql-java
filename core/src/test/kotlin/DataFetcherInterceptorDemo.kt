package demo

import graphql.GraphQL
import graphql.schema.*
import org.intellij.lang.annotations.Language
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.graphql.Cypher
import org.neo4j.graphql.DataFetchingInterceptor
import org.neo4j.graphql.SchemaBuilder
import java.math.BigDecimal
import java.math.BigInteger


fun initBoundSchema(schema: String): GraphQLSchema {
    val driver: Driver = GraphDatabase.driver("bolt://localhost", AuthTokens.basic("neo4j", "test"))

    val dataFetchingInterceptor = object : DataFetchingInterceptor {
        override fun fetchData(env: DataFetchingEnvironment, delegate: DataFetcher<Cypher>): Any {
            val (cypher, params, type, variable) = delegate.get(env)
            return driver.session().use { session ->
                val result = session.run(cypher, params.mapValues { toBoltValue(it.value) })
                if (isListType(type)) {
                    result.list().map { record -> record.get(variable).asObject() }

                } else {
                    result.list().map { record -> record.get(variable).asObject() }.firstOrNull()
                            ?: emptyMap<String, Any>()
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

fun toBoltValue(value: Any?) = when (value) {
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
