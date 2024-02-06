package org.neo4j.graphql.schema.model.inputs

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.translate.WhereResult

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

    fun reduceNestedWhere(extractWhere: (key: String, index: Int, nested: T) -> WhereResult): WhereResult {
        val subqueries = mutableListOf<Statement>()
        val condition = reduceNestedConditions { k, i, n ->
            val (nestedCondition, nestedSubqueries) = extractWhere(k, i, n)
            subqueries.addAll(nestedSubqueries)
            nestedCondition
        }
        return WhereResult(condition, subqueries)
    }

    fun reduceNestedConditions(extractCondition: (key: String, index: Int, nested: T) -> Condition?): Condition? {
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
            joins.forEachIndexed { index, v ->
                extractCondition(op, index, v)?.let { innerCondition = reducer(innerCondition, it) }
            }
            innerCondition?.let { result = result and it }
        }
        return result
    }

    fun evaluateNestedConditions(extractCondition: (nested: T) -> Boolean): Boolean {
        var result: Boolean = true
        nestedConditions.forEach { (op, joins) ->
            if (joins.isEmpty()) {
                return@forEach
            }
            val reducer = when (op) {
                Constants.AND -> { lhs: Boolean?, rhs: Boolean -> lhs?.let { it && rhs } ?: rhs }
                Constants.OR -> { lhs: Boolean?, rhs: Boolean -> lhs?.let { it || rhs } ?: rhs }
                Constants.NOT -> { lhs: Boolean?, rhs: Boolean -> lhs?.let { it && !rhs } ?: !rhs }
                else -> error("only '${Constants.AND}', '${Constants.OR}' or  '${Constants.NOT}' expected")
            }
            var innerCondition: Boolean? = null
            joins.forEach { v -> innerCondition = reducer(innerCondition, extractCondition(v)) }
            innerCondition?.let { rhs -> result = result && rhs }
        }
        return result
    }

    companion object {
        val SPECIAL_WHERE_KEYS = setOf(Constants.OR, Constants.AND, Constants.NOT)
    }
}
