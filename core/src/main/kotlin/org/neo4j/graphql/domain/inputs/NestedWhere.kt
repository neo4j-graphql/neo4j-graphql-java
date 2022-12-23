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

    val and = data[Constants.AND]?.wrapList()?.mapNotNull { nestedWhereFactory(Dict(it)) }

    val or = data[Constants.OR]?.wrapList()?.mapNotNull { nestedWhereFactory(Dict(it)) }

    fun reduceNestedConditions(extractCondition: (key: String, index: Int, nested: T) -> Condition?): Condition? {
        var result: Condition? = null
        listOf(
            Triple(or, Constants.OR, { lhs: Condition?, rhs: Condition -> lhs or rhs }),
            Triple(and, Constants.AND, { lhs: Condition?, rhs: Condition -> lhs and rhs }),
        ).forEach { (joins, key, reducer) ->
            if (!joins.isNullOrEmpty()) {
                var innerCondition: Condition? = null
                joins.forEachIndexed { index, v ->
                    extractCondition(key, index, v)
                        ?.let { innerCondition = reducer(innerCondition, it) }
                }
                innerCondition?.let { result = result and it }
            }
        }
        return result
    }
}
