package org.neo4j.graphql.domain.inputs.delete

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.schema.BaseAugmentationV2

class DeleteResolverInputs(node: Node, args: Map<String, *>) {

    /**
     * Augmented by [BaseAugmentationV2.generateContainerDeleteInputIT]
     */
    val delete = args[Constants.DELETE_FIELD]?.let { DeleteInput.NodeDeleteInput(node, Dict(it)) }

    val where = args[Constants.WHERE]?.let { WhereInput.NodeWhereInput(node, Dict(it)) }
}
