package org.neo4j.graphql.domain.inputs.update

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

class UpdateResolverInputs(node: Node, args: Map<String, *>) {

    /**
     * Augmented by [BaseAugmentationV2.generateWhereIT]
     */
    val where = args[Constants.WHERE]?.let { WhereInput.create(node, it) }

    /**
     * Augmented by [BaseAugmentationV2.generateContainerUpdateIT]
     */
    val update = args[Constants.UPDATE_FIELD]?.let { UpdateInput.NodeUpdateInput(node, Dict(it)) }

    /**
     * Augmented by [BaseAugmentationV2.generateContainerConnectInputIT]
     */
    val connect = args[Constants.CONNECT_FIELD]?.let { ConnectInput.NodeConnectInput(node, Dict(it)) }

    /**
     * Augmented by [BaseAugmentationV2.generateContainerDisconnectInputIT]
     */
    val disconnect = args[Constants.DISCONNECT_FIELD]?.let { DisconnectInput.NodeDisconnectInput(node, Dict(it)) }

    /**
     * Augmented by [BaseAugmentationV2.generateContainerRelationCreateInputIT]
     */
    val create = args[Constants.CREATE_FIELD]?.let { RelationInput.create(node, it) }

    /**
     * Augmented by [BaseAugmentationV2.generateContainerDeleteInputIT]
     */
    val delete = args[Constants.DELETE_FIELD]?.let { DeleteInput.NodeDeleteInput(node, Dict(it)) }

    /**
     * Augmented by [BaseAugmentationV2.generateContainerConnectOrCreateInputIT]
     */
    val connectOrCreate = args[Constants.CONNECT_OR_CREATE_FIELD]?.let { ConnectOrCreateInput.create(node, it) }
}
