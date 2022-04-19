package org.neo4j.graphql.domain.dto

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.SortItem
import org.neo4j.graphql.domain.directives.QueryOptionsDirective

data class OptionsInput(
    val limit: Int? = null,
    val offset: Int? = null,
    val sort: Map<String, SortItem.Direction>? = null,
) {
    fun merge(queryOptions: QueryOptionsDirective?): OptionsInput {
        if (queryOptions == null){
            return this
        }
        val newLimit = when {
            limit != null -> {
                val max = queryOptions.limit?.max
                if (max != null && limit > max) {
                    max
                } else {
                    limit
                }
            }
            else -> queryOptions.limit?.default ?: queryOptions.limit?.max
        }
        return copy(limit = newLimit)
    }

    fun wrapLimitAndOffset(expression: Expression): Expression {
        if (offset != null && limit == null) {
            return Cypher.subListFrom(expression, offset)
        }

        if (limit != null && offset == null) {
            return Cypher.subListUntil(expression, limit)
        }
        if (offset != null && limit != null) {
            return Cypher.subList(expression, offset, offset + limit)
        }
        return expression
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun create(any: Any?) = (any as? Map<String, *>)?.let { create(it) } ?: OptionsInput()

        fun create(map: Map<String, *>) = object {
            val limit: Int? by map
            val offset: Int? by map
            val sort: Map<*, *>? by map
            val data = OptionsInput(limit, offset, sort
                    ?.entries
                    ?.mapNotNull { (k, v) ->
                        val key = k as? String ?: return@mapNotNull null
                        val sort = (v as? String)?.let { SortItem.Direction.valueOf(it) } ?: return@mapNotNull null
                        key to sort
                    }
                    ?.toMap()
            )
        }.data
    }
}
