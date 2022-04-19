package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.Constants

//TODO use DTO instead of maps
data class WhereInput(
    val on: Map<String, WhereInput>?,
    val and: List<WhereInput>?,
    val or: List<WhereInput>?,
    val filter: Map<String, *>,
) {
    companion object {

        private val SPECIAL_KEYS = setOf(Constants.ON, Constants.AND, Constants.OR)
        fun create(any: Any?): WhereInput? {
            val map = any as? Map<*, *> ?: return null
            val on = map[Constants.ON]
                ?.let { it as? Map<*, *> }
                ?.entries
                ?.mapNotNull { (key, value) ->
                    val stringKey = key as? String ?: return@mapNotNull null
                    val nestedInput = create(value) ?: return@mapNotNull null
                    stringKey to nestedInput
                }
                ?.toMap()
                ?.takeIf { it.isNotEmpty() }

            val and = (map[Constants.AND] as? List<*>)
                ?.mapNotNull { create(it) }
                ?.takeIf { it.isNotEmpty() }

            val or = (map[Constants.OR] as? List<*>)
                ?.mapNotNull { create(it) }
                ?.takeIf { it.isNotEmpty() }

            @Suppress("UNCHECKED_CAST") val filter =
                (map.filterKeys { it is String && !SPECIAL_KEYS.contains(it) } as Map<String, *>)

            return WhereInput(on, and, or, filter)
        }
    }

    fun hasRootFilter(): Boolean = filter.isNotEmpty()
            || or?.find { it.hasRootFilter() } != null
            || and?.find { it.hasRootFilter() } != null
}
