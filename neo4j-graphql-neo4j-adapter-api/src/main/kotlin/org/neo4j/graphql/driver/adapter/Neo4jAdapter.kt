package org.neo4j.graphql.driver.adapter

interface Neo4jAdapter {

    enum class Dialect {
        NEO4J_4,
        NEO4J_5,
        NEO4J_5_23,
    }

    fun getDialect(): Dialect = Dialect.NEO4J_5

    @Throws(Exception::class)
    fun executeQuery(cypher: String, params: Map<String, Any?>): List<Map<String, Any?>>

    companion object {
        const val CONTEXT_KEY = "neo4jAdapter"

        val NO_OP = object : Neo4jAdapter {
            override fun executeQuery(cypher: String, params: Map<String, Any?>): List<Map<String, Any?>> {
                return emptyList()
            }
        }
    }
}
