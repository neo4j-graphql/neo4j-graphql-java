package org.neo4j.graphql.schema.relations

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connect.ConnectFieldInput.NodeConnectFieldInput
import org.neo4j.graphql.schema.model.inputs.connect_or_create.ConnectOrCreateFieldInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.schema.model.inputs.create.CreateFieldInput
import org.neo4j.graphql.schema.model.inputs.create.RelationFieldInput
import org.neo4j.graphql.schema.model.inputs.delete.DeleteFieldInput
import org.neo4j.graphql.schema.model.inputs.disconnect.DisconnectFieldInput
import org.neo4j.graphql.schema.model.inputs.update.UpdateFieldInput

/**
 * Augmentation for relations referencing a node
 */
class NodeRelationFieldAugmentations(
    private val ctx: AugmentationContext,
    private val rel: RelationBaseField,
    private val node: Node,
) : RelationFieldBaseAugmentation {

    init {
        if (rel.isUnion) {
            throw IllegalArgumentException("The type of ${rel.getOwnerName()}.${rel.fieldName} is not expected to be an union")
        }
        if (rel.isInterface) {
            throw IllegalArgumentException("The type of ${rel.getOwnerName()}.${rel.fieldName} is not expected to be an interface")
        }
    }

    override fun generateFieldCreateIT() = CreateFieldInput.NodeFieldInput.Augmentation
        .generateFieldNodeFieldInputIT(rel, node, ctx)

    override fun generateFieldConnectIT() = NodeConnectFieldInput.Augmentation
        .generateFieldConnectFieldInputIT(rel, node, ctx)

    override fun generateFieldDeleteIT() = DeleteFieldInput.NodeDeleteFieldInput.Augmentation
        .generateFieldDeleteFieldInputIT(rel, node, ctx)

    override fun generateFieldDisconnectIT() = DisconnectFieldInput.NodeDisconnectFieldInput.Augmentation
        .generateFieldDisconnectFieldInputIT(rel, node, ctx)

    override fun generateFieldRelationCreateIT() = RelationFieldInput.NodeCreateCreateFieldInput.Augmentation
        .generateFieldCreateFieldInputIT(rel, node, ctx)

    override fun generateFieldUpdateIT() = UpdateFieldInput.NodeUpdateFieldInput.Augmentation
        .generateFieldUpdateFieldInputIT(rel, node, ctx)

    override fun generateFieldConnectOrCreateIT() = ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInput.Augmentation
        .generateFieldConnectOrCreateIT(rel, node, ctx)

    override fun generateFieldWhereIT(): String? = WhereInput.NodeWhereInput.Augmentation
        .generateWhereIT(node, ctx)

    override fun generateFieldConnectionWhereIT() = ConnectionWhere.NodeConnectionWhere.Augmentation
        .generateFieldConnectionWhereIT(rel, node, ctx)
}
