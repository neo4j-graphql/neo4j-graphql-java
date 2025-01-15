package org.neo4j.graphql.driver.adapter

import org.neo4j.driver.SessionConfig
import org.neo4j.driver.internal.InternalIsoDuration
import org.neo4j.driver.types.IsoDuration
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Period
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAmount


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
        fun toBoltValue(value: Any?): Any? = when (value) {
            is BigInteger -> value.longValueExact()
            is BigDecimal -> value.toDouble()
            is TemporalAmount -> when (value) {
                is Duration, is Period, is IsoDuration -> value
                else -> temporalAmountToIsoDuration(value)
            }
            is Iterable<*> -> value.map { toBoltValue(it) }
            is Map<*, *> -> value.mapValues { toBoltValue(it.value) }
            else -> value
        }

        private fun temporalAmountToIsoDuration(value: TemporalAmount): InternalIsoDuration {
            var months = 0L
            var days = 0L
            var seconds = 0L
            var nanoseconds = 0
            value.units.forEach { unit ->
                val amount = value.get(unit)
                when (unit as? ChronoUnit ?: TODO("Unsupported TemporalAmount unit: $unit")) {
                    ChronoUnit.NANOS -> nanoseconds += amount.toInt()
                    ChronoUnit.MICROS -> nanoseconds += amount.toInt() * 1000
                    ChronoUnit.MILLIS -> nanoseconds += amount.toInt() * 1000000
                    ChronoUnit.SECONDS -> seconds += amount
                    ChronoUnit.MINUTES -> seconds += amount * 60
                    ChronoUnit.HOURS -> seconds += amount * 3600
                    ChronoUnit.HALF_DAYS -> seconds += amount * 43200
                    ChronoUnit.DAYS -> days += amount
                    ChronoUnit.WEEKS -> days += amount * 7
                    ChronoUnit.MONTHS -> months += amount
                    ChronoUnit.YEARS -> months += amount * 12
                    else -> error("Unsupported TemporalAmount unit: $unit")
                }
            }
            return InternalIsoDuration(months, days, seconds, nanoseconds)
        }
    }
}
