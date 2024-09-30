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

    override fun executeQuery(cypher: String, params: Map<String, Any?>): List<Map<String, Any?>> {
        return driver.session(SessionConfig.forDatabase(database)).use { session ->
            session.run(cypher, params.mapValues { toBoltValue(it.value) })
                .list()
                .map { it.asMap() }
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
