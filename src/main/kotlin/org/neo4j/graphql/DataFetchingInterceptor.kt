package org.neo4j.graphql

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

/**
 * Interceptor to hook in database driver binding
 */
interface DataFetchingInterceptor {

    /**
     * Called by the Graphql runtime for each method augmented by this library. The custom code should call the delegate
     * to get a cypher query. This query can then be forwarded to the neo4j driver to retrieve the data.
     * The method then returns the fully parsed result.
     */
    @Throws(Exception::class)
    fun fetchData(env: DataFetchingEnvironment, delegate: DataFetcher<Cypher>): Any?
}