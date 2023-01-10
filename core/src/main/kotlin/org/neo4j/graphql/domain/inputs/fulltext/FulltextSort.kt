package org.neo4j.graphql.domain.inputs.fulltext

import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asDescription
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.inputs.options.SortInput
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation

class FulltextSort {

    object Augmentation {

        fun generateFulltextSort(node: Node, ctx: AugmentationContext) = ctx.getOrCreateInputObjectType("${node.name}${Constants.InputTypeSuffix.FulltextSort}", {
            description("The input for sorting a fulltext query on an index of ${node.name}".asDescription())
        }) { fields, _ ->

            SortInput.Companion.Augmentation
                .generateSortIT(node, ctx)
                ?.let { fields += ctx.inputValue(node.name.lowercase(), it.asType()) }

            fields += ctx.inputValue(Constants.SCORE, Constants.Types.SortDirection)
        }
            ?: throw IllegalStateException("Expected ${node.name}${Constants.InputTypeSuffix.FulltextSort} to have fields")
    }
}
