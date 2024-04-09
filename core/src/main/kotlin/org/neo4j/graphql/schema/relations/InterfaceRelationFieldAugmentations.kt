package org.neo4j.graphql.schema.relations

import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connect.ConnectFieldInput.InterfaceConnectFieldInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.schema.model.inputs.create.CreateFieldInput
import org.neo4j.graphql.schema.model.inputs.create.RelationFieldInput
import org.neo4j.graphql.schema.model.inputs.delete.DeleteFieldInput
import org.neo4j.graphql.schema.model.inputs.disconnect.DisconnectFieldInput
import org.neo4j.graphql.schema.model.inputs.update.UpdateFieldInput

/**
 * Augmentation for relations referencing an interface
 */
class InterfaceRelationFieldAugmentations(
    private val ctx: AugmentationContext,
    private val rel: RelationBaseField,
    private val interfaze: Interface,
) : RelationFieldBaseAugmentation {

    override fun generateFieldCreateIT() = CreateFieldInput.InterfaceFieldInput.Augmentation
        .generateFieldCreateIT(rel, interfaze, ctx)

    override fun generateFieldConnectIT() = InterfaceConnectFieldInput.Augmentation
        .generateFieldConnectIT(rel, interfaze, ctx)

    override fun generateFieldDeleteIT() = DeleteFieldInput.InterfaceDeleteFieldInput.Augmentation
        .generateFieldDeleteIT(rel, interfaze, ctx)

    override fun generateFieldDisconnectIT() = DisconnectFieldInput.InterfaceDisconnectFieldInput.Augmentation
        .generateFieldDisconnectIT(rel, interfaze, ctx)

    override fun generateFieldRelationCreateIT() = RelationFieldInput.InterfaceCreateFieldInput.Augmentation
        .generateFieldRelationCreateIT(rel, interfaze, ctx)

    override fun generateFieldConnectOrCreateIT(): String? = null

    override fun generateFieldUpdateIT() = UpdateFieldInput.InterfaceUpdateFieldInput.Augmentation
        .generateFieldUpdateIT(rel, interfaze, ctx)

    override fun generateFieldWhereIT(): String? = WhereInput.InterfaceWhereInput.Augmentation
        .generateFieldWhereIT(interfaze, ctx)

    override fun generateFieldConnectionWhereIT() = ConnectionWhere.InterfaceConnectionWhere.Augmentation
        .generateFieldConnectionWhereIT(rel, interfaze, ctx)
}
