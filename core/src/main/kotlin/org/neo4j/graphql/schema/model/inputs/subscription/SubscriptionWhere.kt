package org.neo4j.graphql.schema.model.inputs.subscription

import org.neo4j.graphql.domain.Entity
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.WhereInput

interface SubscriptionWhere {

    class NodeSubscriptionWhere : SubscriptionWhere {

        object Augmentation : AugmentationBase {
            fun generateWhereIT(node: Node, ctx: AugmentationContext): String? =
                ctx.getOrCreateInputObjectType(node.namings.subscriptionWhereInputTypeName) { fields, name ->
                    WhereInput.FieldContainerWhereInput.Augmentation
                        .addWhereFields(name, node.fields.filterIsInstance<ScalarField>(), ctx, fields)
                }
        }
    }

    class InterfaceSubscriptionWhere : SubscriptionWhere {

        object Augmentation : AugmentationBase {

            fun generateWhereIT(interfaze: Interface, ctx: AugmentationContext): String? =
                ctx.getOrCreateInputObjectType(interfaze.namings.subscriptionWhereInputTypeName) { fields, name ->

                    WhereInput.FieldContainerWhereInput.Augmentation
                        .addWhereFields(name, interfaze.fields.filterIsInstance<ScalarField>(), ctx, fields)

                    if (fields.isNotEmpty()) {
                        ctx.addTypenameEnum(interfaze, fields)
                    }
                }
        }
    }

    object Augmentation : AugmentationBase {

        fun generateWhereIT(entity: Entity, ctx: AugmentationContext): String? =
            entity.extractOnTarget(
                { NodeSubscriptionWhere.Augmentation.generateWhereIT(it, ctx) },
                { InterfaceSubscriptionWhere.Augmentation.generateWhereIT(it, ctx) },
                { null }
            )
    }
}