package org.neo4j.graphql.schema.model.inputs.fulltext

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asDescription
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.WhereInput

class FulltextWhere {

    object Augmentation : AugmentationBase {

        fun generateFulltextWhere(node: Node, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType(node.namings.fulltextTypeNames.where, {
                description("The input for filtering a fulltext query on an index of ${node.name}".asDescription())
            }) { fields, _ ->
                WhereInput.NodeWhereInput.Augmentation.generateWhereIT(node, ctx)
                    ?.let { fields += inputValue(node.name.lowercase(), it.asType()) }
                fields += inputValue(Constants.SCORE, Constants.Types.FloatWhere)
            }
                ?: throw IllegalStateException("Expected ${node.namings.fulltextTypeNames.where} to have fields")
    }
}
