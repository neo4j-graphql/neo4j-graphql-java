package org.neo4j.graphql.domain.predicates

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.schema.model.inputs.WhereInput

/**
 * Predicates on a nodes' or relations' property
 */
class ConnectionPredicate(
    val key: String,
    val target: Target,
    val op: ConnectionOperator,
    val value: WhereInput,
) {


    enum class Target(val targetName: String) {
        NODE(Constants.NODE_FIELD),
        EDGE(Constants.EDGE_FIELD),
    }

    enum class ConnectionOperator(
        val suffix: String,
        val conditionCreator: (Condition) -> Condition,
    ) {
        EQUAL("", { it }),
        NOT("_NOT", { it.not() });
    }

    companion object {
        fun getTargetOperationCombinations() = ConnectionPredicate.Target.values()
            .flatMap { target -> ConnectionPredicate.ConnectionOperator.values().map { target to it } }
    }
}
