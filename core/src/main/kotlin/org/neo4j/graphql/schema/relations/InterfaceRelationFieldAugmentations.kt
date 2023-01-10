package org.neo4j.graphql.schema.relations

import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.connect.ConnectFieldInput.InterfaceConnectFieldInput
import org.neo4j.graphql.domain.inputs.connection.ConnectionWhere
import org.neo4j.graphql.domain.inputs.create.CreateFieldInput
import org.neo4j.graphql.domain.inputs.create.RelationFieldInput
import org.neo4j.graphql.domain.inputs.delete.DeleteFieldInput
import org.neo4j.graphql.domain.inputs.disconnect.DisconnectFieldInput
import org.neo4j.graphql.domain.inputs.update.UpdateFieldInput

/**
 * Augmentation for relations referencing an interface
 */
class InterfaceRelationFieldAugmentations(
    private val ctx: AugmentationContext,
    private val rel: RelationField,
    private val interfaze: Interface,
) : RelationFieldBaseAugmentation {


    private val prefix: String = rel.getOwnerName() + rel.fieldName.capitalize()

    // TODO https://github.com/neo4j/graphql/issues/2684
    private val prefix2: String =
        (rel.interfaceField?.getOwnerName() ?: rel.getOwnerName()) + rel.fieldName.capitalize()

    override fun generateFieldCreateIT() = CreateFieldInput.InterfaceFieldInput.Augmentation
        .generateFieldCreateIT(rel, prefix2, interfaze, ctx)

    override fun generateFieldConnectIT() = InterfaceConnectFieldInput.Augmentation
        .generateFieldConnectIT(rel, prefix, interfaze, ctx)

    override fun generateFieldDeleteIT() = DeleteFieldInput.InterfaceDeleteFieldInput.Augmentation
        .generateFieldDeleteIT(rel, prefix, interfaze, ctx)

    override fun generateFieldDisconnectIT() = DisconnectFieldInput.InterfaceDisconnectFieldInput.Augmentation
        .generateFieldDisconnectIT(rel, prefix, interfaze, ctx)

    override fun generateFieldRelationCreateIT() = RelationFieldInput.InterfaceCreateFieldInput.Augmentation
        .generateFieldRelationCreateIT(rel, prefix, interfaze, ctx)

    override fun generateFieldConnectOrCreateIT(): String? = null

    override fun generateFieldUpdateIT() = UpdateFieldInput.InterfaceUpdateFieldInput.Augmentation
        .generateFieldUpdateIT(rel, prefix, interfaze, ctx)

    override fun generateFieldWhereIT(): String? = WhereInput.InterfaceWhereInput.Augmentation
        .generateFieldWhereIT(interfaze, ctx)

    override fun generateFieldConnectionWhereIT() = ConnectionWhere.InterfaceConnectionWhere.Augmentation
        .generateFieldConnectionWhereIT(rel, interfaze, ctx)
}
