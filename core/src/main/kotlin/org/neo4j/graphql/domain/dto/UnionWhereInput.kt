package org.neo4j.graphql.domain.dto

class UnionWhereInput(private val nodesWhere: Map<String, WhereInput>) {
    companion object {

        fun create(any: Any?): UnionWhereInput? {
            val map = any as? Map<*, *> ?: return null
            val nodesWhere = map
                .entries
                .mapNotNull { (key, value) ->
                    val stringKey = key as? String ?: return@mapNotNull null
                    val nestedInput = WhereInput.create(value) ?: return@mapNotNull null
                    stringKey to nestedInput
                }
                .toMap()
                .takeIf { it.isNotEmpty() }
                ?: return null

            return UnionWhereInput(nodesWhere)
        }
    }

    fun getWhere(node: String) = nodesWhere[node]
}
