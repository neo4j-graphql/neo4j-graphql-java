package org.neo4j.graphql.domain.inputs

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.and
import org.neo4j.graphql.or
import org.neo4j.graphql.wrapList

abstract class NestedWhere<T : NestedWhere<T>>(
    data: Dict,
    nestedWhereFactory: (data: Dict) -> T?
) {
    val nestedConditions = data
        .filter { SPECIAL_WHERE_KEYS.contains(it.key) }
        .mapValues { (_, joins) -> joins.wrapList().mapNotNull { nestedWhereFactory(Dict(it)) } }

    val and get() = nestedConditions[Constants.AND]

    val or get() = nestedConditions[Constants.OR]

    fun reduceNestedConditions(extractCondition: (key: String, index: Int, nested: T) -> Condition?): Condition? {
        var result: Condition? = null
        nestedConditions.forEach { (op, joins) ->
            if (joins.isEmpty()) {
                return@forEach
            }
            val reducer = when (op) {
                Constants.AND -> { lhs: Condition?, rhs: Condition -> lhs and rhs }
                Constants.OR -> { lhs: Condition?, rhs: Condition -> lhs or rhs }
                else -> error("only '${Constants.AND}' and '${Constants.OR}' expected")
            }
            var innerCondition: Condition? = null
            joins.forEachIndexed { index, v ->
                extractCondition(op, index, v)?.let { innerCondition = reducer(innerCondition, it) }
            }
            innerCondition?.let { result = result and it }
        }
        return result
    }

    companion object {
        val SPECIAL_WHERE_KEYS = setOf(Constants.OR, Constants.AND)
    }
}
