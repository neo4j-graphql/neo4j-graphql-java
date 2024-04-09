package org.neo4j.graphql.schema.model.inputs.subscription

import org.neo4j.graphql.asType
import org.neo4j.graphql.decapitalize
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.WhereInput

class SubscriptionRelationshipWhere {

    enum class Type() {
        Created,
        Deleted
    }

    object Augmentation : AugmentationBase {

        fun generateSubscriptionConnectionWhereType(node: Node, type: Type, ctx: AugmentationContext): String? {
            val name = when (type) {
                Type.Created -> node.namings.relationshipCreatedSubscriptionWhereInputTypeName
                Type.Deleted -> node.namings.relationshipDeletedSubscriptionWhereInputTypeName
            }
            // TODO use name from operations
            return ctx.getOrCreateInputObjectType(name) { fields, _ ->
                getRelationshipConnectionWhereTypes(node, ctx)?.let {
                    fields += inputValue(type.name.decapitalize() + "Relationship", it.asType())
                }

                SubscriptionWhere.Augmentation.generateWhereIT(node, ctx)?.let {
                    fields += inputValue(node.singular.decapitalize(), it.asType())
                }

                WhereInput.Augmentation.addNestingWhereFields(name, fields)
            }
        }

        private fun getRelationshipConnectionWhereTypes(node: Node, ctx: AugmentationContext): String? =
            ctx.getOrCreateInputObjectType(node.namings.relationshipsSubscriptionWhereInputTypeName) { fields, _ ->
                node.relationFields.forEach { relationField ->
                    SubscriptionWhereFieldInput.Augmentation.generateWhereIT(relationField, ctx)?.let {
                        fields += inputValue(relationField.fieldName, it.asType())
                    }
                }
            }
    }
}
