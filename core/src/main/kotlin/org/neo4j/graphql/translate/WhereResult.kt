package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Statement

// TODO  find better name
data class WhereResult(
    val predicate: Condition?,
    val preComputedSubQueries: List<Statement>
) {
    companion object {
        val EMPTY = WhereResult(null, emptyList())
    }
}
