package org.neo4j.graphql.utils

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Node

object RelevantNodeFilter {

    // TODO alternative with DTO
//    fun filterInterfaceNodes(node: Node, whereInput: WhereInput?) = whereInput == null
//            || whereInput.hasRootFilter()
//            || whereInput.on == null
//            || !whereInput.hasRootFilter() && whereInput.on.containsKey(node.name)


    /**
     * We want to filter nodes if there is either:
     *   - No where input
     *   - There is at least one root filter in addition to _on
     *   - There is no _on filter
     *   - `_on` is the only filter and the current implementation can be found within it
     */
    fun filterInterfaceNodes(node: Node, whereInput: Map<*, *>?) = whereInput == null
            || whereInput.size > 1
            || whereInput[Constants.ON] == null
            || whereInput.size == 1 && (whereInput[Constants.ON] as? Map<*, *>)?.containsKey(node.name) == true
}
