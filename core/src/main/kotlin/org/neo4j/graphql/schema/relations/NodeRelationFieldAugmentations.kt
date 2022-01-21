package org.neo4j.graphql.schema.relations

import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField

/**
 * Augmentation for relations referencing a node
 */
class NodeRelationFieldAugmentations(
    ctx: AugmentationContext,
    val rel: RelationField,
    private val node: Node = rel.node
        ?: throw IllegalArgumentException("no node on ${rel.getOwnerName()}.${rel.fieldName}"),
    private val prefix: String = rel.connectionPrefix + rel.fieldName.capitalize()
) : RelationFieldBaseAugmentation(ctx, rel) {

    init {
        if (rel.isUnion) {
            throw IllegalArgumentException("The type of ${rel.getOwnerName()}.${rel.fieldName} is not expected to be an union")
        }
        if (rel.isInterface) {
            throw IllegalArgumentException("The type of ${rel.getOwnerName()}.${rel.fieldName} is not expected to be an interface")
        }
    }

    override fun generateFieldCreateIT() = generateFieldNodeFieldInputIT(prefix, node)

    override fun generateFieldConnectIT() = generateFieldConnectFieldInputIT(prefix, node)

    override fun generateFieldDeleteIT() = generateFieldDeleteFieldInputIT(prefix, node)

    override fun generateFieldDisconnectIT() = generateFieldDisconnectFieldInputIT(prefix, node)

    override fun generateFieldRelationCreateIT() = generateFieldCreateFieldInputIT(prefix, node)

    override fun generateFieldUpdateIT() = generateFieldUpdateFieldInputIT(prefix, node)

    override fun generateFieldConnectOrCreateIT() = generateFieldConnectOrCreateIT(prefix, node)

    override fun generateFieldWhereIT(): String? = generateWhereIT(node)

    override fun generateFieldConnectionWhereIT() = generateFieldConnectionWhereIT(prefix, node)
}
