package org.neo4j.graphql.domain.predicates

import org.neo4j.cypherdsl.core.Predicates
import org.neo4j.cypherdsl.core.SymbolicName

enum class RelationOperator(
    val suffix: String?,
    val list: Boolean,
    val predicateCreator: (SymbolicName) -> Predicates.OngoingListBasedPredicateFunction,
) {
    ALL("ALL", list = true, Predicates::all),
    NONE("NONE", list = true, Predicates::none),
    SINGLE("SINGLE", list = true, Predicates::single),
    SOME("SOME", list = true, Predicates::any),

    NOT_EQUAL("NOT", list = false, Predicates::none),
    EQUAL(null, list = false, Predicates::any),
}
