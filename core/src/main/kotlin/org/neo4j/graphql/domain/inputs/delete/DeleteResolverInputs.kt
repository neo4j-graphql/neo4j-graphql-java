package org.neo4j.graphql.domain.inputs.delete

import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.connect.ConnectInput
import org.neo4j.graphql.domain.inputs.connect_or_create.ConnectOrCreateInput
import org.neo4j.graphql.domain.inputs.create.CreateFieldInput
import org.neo4j.graphql.domain.inputs.create.CreateInput
import org.neo4j.graphql.domain.inputs.create.RelationInput
import org.neo4j.graphql.domain.inputs.delete.DeleteInput
import org.neo4j.graphql.domain.inputs.disconnect.DisconnectInput
import org.neo4j.graphql.schema.BaseAugmentationV2

class DeleteResolverInputs(node: Node, args: Map<String, *>) {

    /**
     * Augmented by [BaseAugmentationV2.generateContainerDeleteInputIT]
     */
    val delete = args[Constants.DELETE_FIELD]?.let { DeleteInput.NodeDeleteInput(node, Dict(it)) }
}
