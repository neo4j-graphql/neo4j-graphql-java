package org.neo4j.graphql.schema.model.outputs.aggregate

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.makeRequired
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.utils.ResolveTree

class RelationAggregationSelection(
    rel: RelationField,
    val selection: ResolveTree,
) {
    private val aggregateTypeNames = rel.namings.aggregateTypeNames
        ?: error("expect aggregateTypeNames to be set for $rel")

    val count = selection.getFieldOfType(aggregateTypeNames.field, Constants.COUNT)

    val node = selection
        .getSingleFieldOfType(aggregateTypeNames.field, Constants.NODE_FIELD, { rt ->
            val impl = rel.implementingType ?: return@getSingleFieldOfType null
            rt.fieldsByTypeName[aggregateTypeNames.node]?.let { AggregationSelectionFields.create(impl, it) }
        })


    val edge = selection
        .getSingleFieldOfType(aggregateTypeNames.field, Constants.EDGE_FIELD, { rt ->
            val props = rel.properties ?: return@getSingleFieldOfType null
            rt.fieldsByTypeName[aggregateTypeNames.edge]?.let { AggregationSelectionFields.create(props, it) }
        })

    object Augmentation : AugmentationBase {

        fun generateAggregationSelectionOT(rel: RelationField, ctx: AugmentationContext): String? {
            val implementingType = rel.implementingType ?: return null
            val aggregateTypeNames = rel.namings.aggregateTypeNames ?: return null

            return ctx.getOrCreateObjectType(aggregateTypeNames.field) { fields, _ ->

                fields += field(Constants.COUNT, Constants.Types.Int.makeRequired())

                AggregationSelectionFields.Augmentation.createAggregationField(
                    aggregateTypeNames.node,
                    implementingType.fields,
                    ctx
                )
                    ?.let { fields += field(Constants.NODE_FIELD, it.asType()) }

                AggregationSelectionFields.Augmentation.createAggregationField(
                    aggregateTypeNames.edge,
                    rel.properties?.fields ?: emptyList(),
                    ctx
                )
                    ?.let { fields += field(Constants.EDGE_FIELD, it.asType()) }

            } ?: throw IllegalStateException("Expected at least the count field")
        }
    }
}
