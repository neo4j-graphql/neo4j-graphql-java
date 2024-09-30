package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.and

// TODO  find better name
data class WhereResult(
    val predicate: Condition?,
    val preComputedSubQueries: List<Statement> = emptyList()
) {
    infix fun and(other: WhereResult) = WhereResult(
        other.predicate?.let { this.predicate and it } ?: this.predicate,
        this.preComputedSubQueries + other.preComputedSubQueries
    )

    infix fun and(condition: Condition?): WhereResult {
        if (condition == null) return this
        return WhereResult(this.predicate and condition, this.preComputedSubQueries)
    }

    val requiredCondition get() = predicate ?: Cypher.noCondition()

    companion object {
        val EMPTY = WhereResult(null, emptyList())
    }
}
