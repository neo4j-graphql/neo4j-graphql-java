package org.neo4j.graphql.examples.graphqlspringboot.config

import graphql.schema.*
import org.neo4j.cypherdsl.core.renderer.Dialect
import org.neo4j.driver.Driver
import org.neo4j.graphql.DataFetchingInterceptor
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.setQueryContext
import org.neo4j.graphql.OldCypher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Configuration of the DataFetchingInterceptor
 */
@Configuration
open class Neo4jConfiguration {

    /**
     * This interceptor is bound to all the graphql fields generated by the neo4j-graphql-library.
     * Its purpose is the execution of the cypher query and the transformation of the query result.
     */
    @Bean
    open fun dataFetchingInterceptor(driver: Driver): DataFetchingInterceptor {
        return object : DataFetchingInterceptor {
            override fun fetchData(env: DataFetchingEnvironment, delegate: DataFetcher<OldCypher>): Any? {
                // here you can switch to the new neo4j 5 dialect, if required
                env.graphQlContext.setQueryContext(QueryContext(neo4jDialect = Dialect.NEO4J_5))

                val (cypher, params, type, variable) = delegate.get(env)

                return driver.session().executeWrite { tx ->
                    val boltParams = params.mapValues { toBoltValue(it.value) }
                    val result = tx.run(cypher, boltParams)
                    if (isListType(type)) {
                        result.list()
                            .map { record -> record.get(variable).asObject() }
                    } else {
                        result.list()
                            .map { record -> record.get(variable).asObject() }
                            .firstOrNull() ?: emptyMap<String, Any>()
                    }
                }
            }
        }
    }

    companion object {
        private fun toBoltValue(value: Any?) = when (value) {
            is BigInteger -> value.longValueExact()
            is BigDecimal -> value.toDouble()
            else -> value
        }

        private fun isListType(type: GraphQLType?): Boolean = when (type) {
            is GraphQLList -> true
            is GraphQLNonNull -> isListType(type.wrappedType)
            else -> false
        }
    }
}
