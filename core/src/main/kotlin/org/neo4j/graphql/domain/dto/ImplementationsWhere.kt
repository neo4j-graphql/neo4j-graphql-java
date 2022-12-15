package org.neo4j.graphql.domain.dto

import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node

class ImplementationsWhere(data: Map<Node, WhereInput.FieldContainerWhereInput>) :
    PerNodeInput<WhereInput.FieldContainerWhereInput>(data) {
    companion object {

        fun create(interfaze: Interface, data: Map<String, *>): ImplementationsWhere? =
            create(
                ::ImplementationsWhere,
                interfaze,
                data
            ) { node, value -> WhereInput.FieldContainerWhereInput.create(node, value) }
    }
}
