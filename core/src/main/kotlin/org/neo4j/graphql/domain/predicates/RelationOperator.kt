package org.neo4j.graphql.domain.predicates

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.asCypherLiteral

enum class RelationOperator(
    val suffix: String?,
    val list: Boolean,
    val countPredicate: (SymbolicName) -> Condition,
    val whereTransformer: (where: Condition?) -> Condition? = { it },
    val deprecatedAlternative: RelationOperator? = null,
) {
    ALL("ALL", list = true, { it.eq(0.asCypherLiteral()) }, { it?.not() }),
    NONE("NONE", list = true, { it.eq(0.asCypherLiteral()) }),
    SINGLE("SINGLE", list = true, { it.eq(1.asCypherLiteral()) }),
    SOME("SOME", list = true, { it.gt(0.asCypherLiteral()) }),

    //TODO remove https://github.com/neo4j/graphql/issues/144
    LIST_NOT("NOT", list = true, { it.eq(1.asCypherLiteral()) }, deprecatedAlternative = NONE),
    //TODO remove https://github.com/neo4j/graphql/issues/144
    LIST_EQ(null, list = true, { it.gt(0.asCypherLiteral()) }, deprecatedAlternative = SOME),

    NOT_EQUAL("NOT", list = false, { it.eq(0.asCypherLiteral()) }),
    EQUAL(null, list = false, { it.eq(1.asCypherLiteral()) }),
}
