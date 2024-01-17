package org.neo4j.graphql.schema.model.outputs.aggregate

import graphql.language.NonNullType
import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.utils.IResolveTree

class AggregationSelection(node: Node, selection: IResolveTree) {

    val count = selection.getFieldOfType(node.operations.aggregateTypeNames.selection, Constants.COUNT)

    val fieldSelection = selection.fieldsByTypeName[node.operations.aggregateTypeNames.selection]
        ?.let { fields -> AggregationSelectionFields.create(node, fields, { it != Constants.COUNT }) }
        ?: emptyMap()

    object Augmentation : AugmentationBase {

        fun addAggregationSelectionType(implementingType: ImplementingType, ctx: AugmentationContext): String {
            return ctx.getOrCreateObjectType(implementingType.operations.aggregateTypeNames.selection) { fields, _ ->
                fields += field(Constants.COUNT, NonNullType(Constants.Types.Int))

                // TODO potential conflict with count
                fields += AggregationSelectionFields.Augmentation.getAggregationFields(implementingType.fields, ctx)

            } ?: throw IllegalStateException("Expected at least the ${Constants.COUNT} field")
        }
    }
}
