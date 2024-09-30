package org.neo4j.graphql.schema.relations

import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.Union
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere

/**
 * Augmentation for relations referencing a union
 */
class UnionRelationFieldAugmentations(
    private val ctx: AugmentationContext,
    private val rel: RelationBaseField,
    private val union: Union,
) : RelationFieldBaseAugmentation {

    private val unionNodes: Collection<Node> = union.nodes.values

    override fun generateFieldWhereIT() = WhereInput.UnionWhereInput.Augmentation.generateWhereIT(union, ctx)

    override fun generateFieldConnectionWhereIT() =
        ctx.getOrCreateInputObjectType(rel.namings.unionConnectionUnionWhereTypeName) { fields, _ ->
            unionNodes.forEach { node ->
                ConnectionWhere.NodeConnectionWhere.Augmentation
                    .generateFieldConnectionWhereIT(rel, node, ctx)
                    ?.let { fields += ctx.inputValue(node.name, it.asType()) }
            }
        }
}
