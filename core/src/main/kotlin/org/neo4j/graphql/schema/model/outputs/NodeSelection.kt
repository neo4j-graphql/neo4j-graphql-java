package org.neo4j.graphql.schema.model.outputs

import graphql.language.FieldDefinition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.NonNull
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.field_arguments.RelationFieldAggregateInputArgs
import org.neo4j.graphql.schema.model.outputs.aggregate.RelationAggregationSelection
import org.neo4j.graphql.utils.IResolveTree

class NodeSelection(
    val node: Node,
    val resolveTree: IResolveTree
) : FieldContainerSelection() {

    object Augmentation : AugmentationBase {

        fun generateNodeSelection(node: Node, ctx: AugmentationContext) = ctx.getOrCreateObjectType(node.name,
            init = {
                description(node.description)
                comments(node.comments)
                directives(node.annotations.otherDirectives)
                implementz(node.interfaces.map { it.name.asType() })
                if (node.hasRelayId) {
                    implementz(Constants.Types.Node)
                }
            },
            initFields = { fields, _ ->
                node.fields.forEach { field ->
                    if (field.annotations.selectable?.onRead != false) {
                        fields += FieldContainerSelection.Augmentation.mapField(field, ctx)
                    }
                    if (field.annotations.relayId != null) {
                        fields += field(Constants.ID_FIELD, Constants.Types.ID.NonNull)
                    }
                    if (field is RelationField) {
                        generateAggregationFields(field, ctx, fields)
                    }
                }
            }
        )

        private fun generateAggregationFields(
            relationField: RelationField,
            ctx: AugmentationContext,
            fields: MutableList<FieldDefinition>
        ) {
            if (relationField.annotations.relationship?.aggregate == false) {
                return
            }
            RelationAggregationSelection.Augmentation
                .generateAggregationSelectionOT(relationField, ctx)
                ?.let { aggr ->

                    fields += field(relationField.namings.aggregateTypeName, aggr.asType()) {

                        RelationFieldAggregateInputArgs.Augmentation
                            .getFieldArguments(relationField, ctx)
                            .let { inputValueDefinitions(it) }

                        relationField.deprecatedDirective?.let { directive(it) }
                    }
                }
        }
    }
}
