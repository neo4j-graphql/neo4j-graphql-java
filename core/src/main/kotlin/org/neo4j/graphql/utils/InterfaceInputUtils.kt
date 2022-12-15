package org.neo4j.graphql.utils

import org.neo4j.graphql.Constants
import org.neo4j.graphql.nestedMap

//TODO remove
object InterfaceInputUtils {

    fun spiltInput(input: Map<*, *>?, nodeName: String, isInterface: Boolean):
            Pair<Any?, Map<Any?, Any?>> {
        val inputOnForNode = input.nestedMap(Constants.ON)?.get(nodeName)
        val inputExcludingOnForNode = input
            ?.toMutableMap()
            ?.apply {
                keys.remove(Constants.ON)
                if (isInterface && inputOnForNode != null) {
                    val onArray = inputOnForNode as? List<*> ?: listOf(inputOnForNode)
                    // these fields will be handled via the `inputOnForNode` items
                    keys.removeAll(onArray.flatMapTo(hashSetOf(), { (it as? Map<*, *>)?.keys ?: emptySet() }))
                }
            }
            ?: emptyMap()
        return inputOnForNode to inputExcludingOnForNode
    }
}
