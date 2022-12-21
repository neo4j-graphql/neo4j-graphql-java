package org.neo4j.graphql.domain.inputs.options

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.SortItem
import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.directives.QueryOptionsDirective
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.wrapList

data class OptionsInput(
    val limit: Int? = null,
    val offset: Int? = null,
    val sort: List<Map<String, SortItem.Direction>>? = null,
) {
    fun merge(queryOptions: QueryOptionsDirective?): OptionsInput {
        if (queryOptions == null) {
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

        fun create(any: Any?) = any?.let { Dict(it) }?.let { create(it) } ?: OptionsInput()

        fun create(map: Map<String, *>) = OptionsInput(
            map[Constants.LIMIT] as? Int,
            map[Constants.OFFSET] as? Int,
            map[Constants.SORT]?.wrapList()?.map {
                Dict(it).entries
                    .mapNotNull { (k, v) ->
                        val key = k as? String ?: return@mapNotNull null
                        val sort = (v as? String)?.let { SortItem.Direction.valueOf(it) } ?: return@mapNotNull null
                        key to sort
                    }
                    .toMap()
            }

        )
    }
}
