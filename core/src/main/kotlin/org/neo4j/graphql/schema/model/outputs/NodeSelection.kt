package org.neo4j.graphql.schema.model.outputs

import graphql.language.FieldDefinition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.field_arguments.RelationFieldAggregateInputArgs
import org.neo4j.graphql.schema.model.outputs.aggregate.RelationAggregationSelection

class NodeSelection : FieldContainerSelection() {

    object Augmentation : AugmentationBase {

        fun generateNodeSelection(node: Node, ctx: AugmentationContext) = ctx.getOrCreateObjectType(node.name,
            init = {
                description(node.description)
                comments(node.comments)
                directives(node.otherDirectives)
                implementz(node.interfaces.map { it.name.asType() })
            },
            initFields = { fields, _ ->
                node.fields
                    .filterNot { it.writeonly }
                    .forEach { field ->
                        fields += FieldContainerSelection.Augmentation
                            .mapField(field, ctx)

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
            RelationAggregationSelection.Augmentation
                .generateAggregationSelectionOT(relationField, ctx)
                ?.let { aggr ->

                    fields += field(relationField.fieldName + Constants.FieldSuffix.Aggregate, aggr.asType()) {

                        RelationFieldAggregateInputArgs.Augmentation
                            .getFieldArguments(relationField, ctx)
                            .let { inputValueDefinitions(it) }

                        relationField.deprecatedDirective?.let { directive(it) }
                    }
                }
        }
    }
}
