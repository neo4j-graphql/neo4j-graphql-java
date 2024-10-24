package org.neo4j.graphql.driver.adapter

import org.neo4j.driver.SessionConfig
import java.math.BigDecimal
import java.math.BigInteger


class Neo4jDriverAdapter @JvmOverloads constructor(
    private val driver: org.neo4j.driver.Driver,
    private val dialect: Neo4jAdapter.Dialect = Neo4jAdapter.Dialect.NEO4J_5,
    private val database: String? = "neo4j",
) : Neo4jAdapter {

    override fun getDialect() = dialect

    override fun executeQuery(cypher: String, params: Map<String, Any?>): Neo4jAdapter.QueryResult {
        return driver.session(SessionConfig.forDatabase(database)).use { session ->
            val result = session.run(cypher, params.mapValues { toBoltValue(it.value) })
            val data = result
                .list()
                .map { it.asMap() }
            val counters = result.consume().counters()
            Neo4jAdapter.QueryResult(
                data = data,
                statistics = Neo4jAdapter.QueryStatistics(
                    nodesCreated = counters.nodesCreated(),
                    nodesDeleted = counters.nodesDeleted(),
                    relationshipsCreated = counters.relationshipsCreated(),
                    relationshipsDeleted = counters.relationshipsDeleted(),
                )
            )
        }
    }

    companion object {
        private fun toBoltValue(value: Any?) = when (value) {
            is BigInteger -> value.longValueExact()
            is BigDecimal -> value.toDouble()
            else -> value
        }
    }
}
