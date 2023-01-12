package org.neo4j.graphql.schema.model.inputs

import org.neo4j.graphql.wrapList

abstract class InputListWrapper<ITEM>(data: List<ITEM>) : List<ITEM> by data {
    companion object {
        fun <LIST: InputListWrapper<ITEM>, ITEM> create(
            value: Any?,
            listFactory: (List<ITEM>) -> LIST,
            itemFactory: (Any) -> ITEM
        ): LIST {
            return (value ?: emptyList<Any>())
                .wrapList()
                .map { itemFactory(it) }
                .let { listFactory(it) }
        }
    }
}
