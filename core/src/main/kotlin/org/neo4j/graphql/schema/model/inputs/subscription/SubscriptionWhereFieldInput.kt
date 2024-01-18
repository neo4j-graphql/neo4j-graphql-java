package org.neo4j.graphql.schema.model.inputs.subscription

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.WhereInput

interface SubscriptionWhereFieldInput {

    class ImplementingTypeSubscriptionWhereFieldInput : SubscriptionWhereFieldInput {

        object Augmentation : AugmentationBase {

            fun generateWhereIT(field: RelationField, ctx: AugmentationContext): String? =
                generateWhereIT(field, field.implementingType, field.operations.subscriptionWhereInputTypeName, ctx)

            // TODO do not create different types with same fields
            fun generateWhereIT(
                field: RelationField,
                implementingType: ImplementingType?,
                name: String,
                ctx: AugmentationContext
            ): String? {
                if (implementingType == null) {
                    return null
                }
                return ctx.getOrCreateInputObjectType(name) { fields, _ ->

                    SubscriptionWhere.Augmentation.generateWhereIT(implementingType, ctx)?.let {
                        fields += inputValue(Constants.NODE_FIELD, it.asType())
                    }
                    getEdgeSubscriptionWhere(field, ctx)?.let {
                        fields += inputValue(Constants.EDGE_FIELD, it.asType())
                    }
                }
            }

            private fun getEdgeSubscriptionWhere(
                rel: RelationField,
                ctx: AugmentationContext,
            ): String? {
                if (rel.properties == null) {
                    return null
                }
                return ctx.getOrCreateInputObjectType(rel.operations.edgeSubscriptionWhereInputTypeName) { fields, name ->
                    fields += WhereInput.FieldContainerWhereInput.Augmentation
                        .getWhereFields(name, rel.properties.fields, ctx)
                }
            }
        }
    }

    class UnionSubscriptionWhereFieldInput : SubscriptionWhereFieldInput {
        object Augmentation : AugmentationBase {

            fun generateWhereIT(rel: RelationField, ctx: AugmentationContext): String? =
                ctx.getOrCreateInputObjectType(rel.operations.subscriptionWhereInputTypeName) { fields, _ ->
                    rel.union?.nodes?.values?.forEach { node ->
                        ImplementingTypeSubscriptionWhereFieldInput.Augmentation.generateWhereIT(
                            rel,
                            node,
                            rel.operations.getToUnionSubscriptionWhereInputTypeName(node),
                            ctx
                        )?.let {
                            fields += inputValue(node.name, it.asType())
                        }
                    }
                }
        }
    }


    object Augmentation : AugmentationBase {

        fun generateWhereIT(field: RelationField, ctx: AugmentationContext): String? = field
            .extractOnTarget(
                {
                    ImplementingTypeSubscriptionWhereFieldInput.Augmentation.generateWhereIT(field, ctx)
                },
                { UnionSubscriptionWhereFieldInput.Augmentation.generateWhereIT(field, ctx) }
            )
    }
}
