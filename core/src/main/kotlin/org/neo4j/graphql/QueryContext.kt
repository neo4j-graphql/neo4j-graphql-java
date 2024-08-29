package org.neo4j.graphql

import org.neo4j.cypherdsl.core.renderer.Dialect

data class QueryContext @JvmOverloads constructor(
    /**
     * if true the <code>__typename</code> will always be returned for interfaces, no matter if it was queried or not
     */
    var queryTypeOfInterfaces: Boolean = false,

    /**
     * If set alternative approaches for query translation will be used
     */
    var optimizedQuery: Set<OptimizationStrategy>? = null,

    var neo4jDialect: Dialect = Dialect.NEO4J_5

) {
    enum class OptimizationStrategy {
        /**
         * If used, filter queries will be converted to cypher matches
         */
        FILTER_AS_MATCH
    }

    companion object {
        const val KEY = "Neo4jGraphQLQueryContext"
    }
}
