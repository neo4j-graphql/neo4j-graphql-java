package org.neo4j.graphql.domain.inputs.fulltext

import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asDescription
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation

class FulltextWhere {

    object Augmentation {

        fun generateFulltextWhere(node: Node, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType("${node.name}${Constants.InputTypeSuffix.FulltextWhere}", {
                description("The input for filtering a fulltext query on an index of ${node.name}".asDescription())
            }) { fields, _ ->
                WhereInput.NodeWhereInput.Augmentation.generateWhereIT(node, ctx)
                    ?.let { fields += ctx.inputValue(node.name.lowercase(), it.asType()) }
                fields += ctx.inputValue(Constants.SCORE, Constants.Types.FloatWhere)
            }
                ?: throw IllegalStateException("Expected ${node.name}${Constants.InputTypeSuffix.FulltextWhere} to have fields")
    }
}
