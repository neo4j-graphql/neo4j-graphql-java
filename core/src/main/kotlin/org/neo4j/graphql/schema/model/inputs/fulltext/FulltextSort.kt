package org.neo4j.graphql.schema.model.inputs.fulltext

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asDescription
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.options.SortInput

class FulltextSort {

    object Augmentation : AugmentationBase {

        fun generateFulltextSort(node: Node, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType("${node.name}${Constants.InputTypeSuffix.FulltextSort}", {
                description("The input for sorting a fulltext query on an index of ${node.name}".asDescription())
            }) { fields, _ ->

                SortInput.Companion.Augmentation
                    .generateSortIT(node, ctx)
                    ?.let { fields += inputValue(node.name.lowercase(), it.asType()) }

                fields += inputValue(Constants.SCORE, Constants.Types.SortDirection)
            }
                ?: throw IllegalStateException("Expected ${node.name}${Constants.InputTypeSuffix.FulltextSort} to have fields")
    }
}
