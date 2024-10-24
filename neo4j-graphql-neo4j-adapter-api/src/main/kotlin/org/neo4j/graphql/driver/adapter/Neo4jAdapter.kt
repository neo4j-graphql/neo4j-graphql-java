package org.neo4j.graphql.driver.adapter

interface Neo4jAdapter {

    enum class Dialect {
        NEO4J_4,
        NEO4J_5,
        NEO4J_5_23,
    }

    data class QueryStatistics(
        val nodesCreated: Int = 0,
        val nodesDeleted: Int = 0,
        val relationshipsCreated: Int = 0,
        val relationshipsDeleted: Int = 0,
    )

    data class QueryResult(
        val data: List<Map<String, Any?>>,
        val statistics: QueryStatistics
    ) {
        companion object {
            val EMPTY = QueryResult(emptyList(), QueryStatistics())
        }
    }

    fun getDialect(): Dialect = Dialect.NEO4J_5

    @Throws(Exception::class)
    fun executeQuery(cypher: String, params: Map<String, Any?>): QueryResult

    companion object {
        const val CONTEXT_KEY = "neo4jAdapter"

        val NO_OP = object : Neo4jAdapter {
            override fun executeQuery(cypher: String, params: Map<String, Any?>): QueryResult {
                return QueryResult.EMPTY
            }
        }
    }
}
