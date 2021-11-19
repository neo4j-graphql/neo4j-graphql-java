package org.neo4j.graphql

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

/**
 * Interceptor to hook in database driver binding including statistics
 */
interface DataFetchingInterceptorWithStatistics : DataFetchingInterceptor {

    /**
     * Called by the Graphql runtime for each method augmented by this library. The custom code should call the delegate
     * to get a cypher query. This query can then be forwarded to the neo4j driver to retrieve the data.
     * The method then returns the fully parsed result.
     */
    @Throws(Exception::class)
    fun fetchDataWithStatistic(env: DataFetchingEnvironment, delegate: DataFetcher<Cypher>): Result?

    override fun fetchData(env: DataFetchingEnvironment, delegate: DataFetcher<Cypher>): Any? = fetchDataWithStatistic(env, delegate)?.data

    data class Result(val data: Any?, val statistics: Statistics)

    interface Statistics {
        fun getPropertiesSet(): Int
        fun getNodesCreated(): Int
        fun getNodesDeleted(): Int
        fun getRelationshipsCreated(): Int
        fun getRelationshipsDeleted(): Int
    }
}
