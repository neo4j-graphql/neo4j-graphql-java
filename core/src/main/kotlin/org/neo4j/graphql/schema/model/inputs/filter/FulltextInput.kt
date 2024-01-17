package org.neo4j.graphql.schema.model.inputs.filter

import org.neo4j.graphql.Constants
import org.neo4j.graphql.NonNull
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict

class FulltextInput(data: Dict) {
    val phrase = data.nestedObject(Constants.FULLTEXT_PHRASE) as String

    // TODO remove?
    val score = data.nestedObject(Constants.FULLTEXT_SCORE_EQUAL) as? Number

    object Augmentation : AugmentationBase {

        fun generateFulltextInput(node: Node, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType(node.operations.fullTextInputTypeName) { fields, _ ->
                node.annotations.fulltext?.indexes?.forEach { index ->
                    val indexName = index.indexName
                    generateFullTextIndexInputType(node, indexName, ctx)?.let {
                        fields += inputValue(indexName, it.asType())
                    }
                }
            }


        private fun generateFullTextIndexInputType(node: Node, indexName: String, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType(node.operations.getFullTextIndexInputTypeName(indexName)) { fields, _ ->
                fields += inputValue(Constants.FULLTEXT_PHRASE, Constants.Types.String.NonNull)
            }
    }
}
