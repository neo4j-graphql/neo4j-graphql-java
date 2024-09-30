package org.neo4j.graphql.schema.relations

import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere

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

    override fun generateFieldWhereIT(): String? = WhereInput.NodeWhereInput.Augmentation
        .generateWhereIT(node, ctx)

    override fun generateFieldConnectionWhereIT() = ConnectionWhere.NodeConnectionWhere.Augmentation
        .generateFieldConnectionWhereIT(rel, node, ctx)
}
