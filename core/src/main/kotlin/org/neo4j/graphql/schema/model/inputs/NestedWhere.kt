package org.neo4j.graphql.schema.model.inputs

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.graphql.*

abstract class NestedWhere<T : NestedWhere<T>>(
    data: Dict,
    nestedWhereFactory: (data: Dict) -> T?
) {
    val nestedConditions = data
        .filter { SPECIAL_WHERE_KEYS.contains(it.key) }
        .mapValues { (_, joins) -> joins.wrapList().toDict().mapNotNull { nestedWhereFactory(it) } }

    val and get() = nestedConditions[Constants.AND]

    val or get() = nestedConditions[Constants.OR]

    val not get() = nestedConditions[Constants.NOT]

    fun reduceNestedConditions(extractCondition: (nested: T) -> Condition?): Condition? {
        var result: Condition? = null
        nestedConditions.forEach { (op, joins) ->
            if (joins.isEmpty()) {
                return@forEach
            }
            val reducer = when (op) {
                Constants.AND -> { lhs: Condition?, rhs: Condition -> lhs and rhs }
                Constants.OR -> { lhs: Condition?, rhs: Condition -> lhs or rhs }
                Constants.NOT -> { lhs: Condition?, rhs: Condition -> lhs and rhs.not() }
                else -> error("only '${Constants.AND}', '${Constants.OR}' or  '${Constants.NOT}' expected")
            }
            var innerCondition: Condition? = null
            joins.forEach { v ->
                extractCondition(v)?.let { innerCondition = reducer(innerCondition, it) }
            }
            innerCondition?.let { result = result and it }
        }
        return result
    }

    companion object {
        val SPECIAL_WHERE_KEYS = setOf(Constants.OR, Constants.AND, Constants.NOT)
    }
}
