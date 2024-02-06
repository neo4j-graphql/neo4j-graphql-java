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
                    fields += WhereInput.FieldContainerWhereInput.Augmentation
                        .getWhereFields(name, node.fields.filterIsInstance<ScalarField>(), ctx)
                }
        }
    }

    class InterfaceSubscriptionWhere : SubscriptionWhere {

        object Augmentation : AugmentationBase {

            fun generateWhereIT(interfaze: Interface, ctx: AugmentationContext): String? =
                ctx.getOrCreateInputObjectType(interfaze.namings.subscriptionWhereInputTypeName) { fields, name ->

                    fields += WhereInput.FieldContainerWhereInput.Augmentation
                        .getWhereFields(name, interfaze.fields.filterIsInstance<ScalarField>(), ctx)

                    if (fields.isNotEmpty()) {
                        // TODO create ticket for adding this filtering for subscriptions as well
                        if (ctx.schemaConfig.experimental) {
                            ctx.addTypenameEnum(interfaze, fields)
                        } else {
                            ctx.addOnField(interfaze,
                                interfaze.namings.implementationsSubscriptionWhereInputTypeName,
                                fields,
                                asList = false,
                                { node -> NodeSubscriptionWhere.Augmentation.generateWhereIT(node, ctx) })
                        }
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
