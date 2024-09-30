package org.neo4j.graphql.schema.relations

import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere

/**
 * Augmentation for relations referencing an interface
 */
class InterfaceRelationFieldAugmentations(
    private val ctx: AugmentationContext,
    private val rel: RelationBaseField,
    private val interfaze: Interface,
) : RelationFieldBaseAugmentation {

    override fun generateFieldWhereIT(): String? = WhereInput.InterfaceWhereInput.Augmentation
        .generateFieldWhereIT(interfaze, ctx)

    override fun generateFieldConnectionWhereIT() = ConnectionWhere.InterfaceConnectionWhere.Augmentation
        .generateFieldConnectionWhereIT(rel, interfaze, ctx)
}
