package org.neo4j.graphql.domain.inputs.create

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.BaseAugmentationV2
import org.neo4j.graphql.wrapList

class CreateResolverInputs(node: Node, args: Map<String, *>) {

    /**
     * Augmented by [BaseAugmentationV2.generateContainerCreateInputIT]
     */
    val input = args[Constants.INPUT_FIELD]
        ?.wrapList()
        ?.map { CreateInput.create(node, it) }

}
