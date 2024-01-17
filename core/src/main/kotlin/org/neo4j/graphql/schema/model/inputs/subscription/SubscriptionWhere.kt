package org.neo4j.graphql.schema.model.inputs.subscription

import org.neo4j.graphql.Constants
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
                ctx.getOrCreateInputObjectType(node.operations.subscriptionWhereInputTypeName) { fields, name ->
                    fields += WhereInput.FieldContainerWhereInput.Augmentation
                        .getWhereFields(
                            node.name,
                            node.fields.filterIsInstance<ScalarField>(),
                            ctx,
                            whereName = name
                        )
                }
        }
    }

    class InterfaceSubscriptionWhere : SubscriptionWhere {

        object Augmentation : AugmentationBase {

            fun generateWhereIT(interfaze: Interface, ctx: AugmentationContext): String? =
                ctx.getOrCreateInputObjectType(interfaze.operations.subscriptionWhereInputTypeName) { fields, name ->

                    fields += WhereInput.FieldContainerWhereInput.Augmentation
                        .getWhereFields(
                            interfaze.name,
                            interfaze.fields.filterIsInstance<ScalarField>(),
                            ctx,
                            whereName = name
                        )

                    if (fields.isNotEmpty()) {
                        // TODO create ticket for adding this filtering for subscriptions as well
                        if (ctx.schemaConfig.experimental) {
                            ctx.addTypenameEnum(interfaze, fields)
                        } else {
                            ctx.addOnField(interfaze,
                                Constants.InputTypeSuffix.ImplementationsSubscriptionWhere,
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
