package org.neo4j.graphql.schema.model.inputs.subscription

import org.neo4j.graphql.asType
import org.neo4j.graphql.decapitalize
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.WhereInput

class SubscriptionRelationshipWhere {

    enum class Type {
        Created,
        Deleted
    }

    object Augmentation : AugmentationBase {

        fun generateSubscriptionConnectionWhereType(node: Node, type: Type, ctx: AugmentationContext): String? =
            // TODO use name from operations
            ctx.getOrCreateInputObjectType("${node.name}Relationship${type}SubscriptionWhere") { fields, name ->
                getRelationshipConnectionWhereTypes(node, ctx)?.let {
                    fields += inputValue(type.name.decapitalize() + "Relationship", it.asType())
                }

                SubscriptionWhere.Augmentation.generateWhereIT(node, ctx)?.let {
                    fields += inputValue(node.singular.decapitalize(), it.asType())
                }

                WhereInput.Augmentation.addNestingWhereFields(name, fields, ctx)
            }

        private fun getRelationshipConnectionWhereTypes(node: Node, ctx: AugmentationContext): String? =
            ctx.getOrCreateInputObjectType(node.operations.relationshipsSubscriptionWhereInputTypeName) { fields, _ ->
                node.relationFields.forEach { relationField ->
                    SubscriptionWhereFieldInput.Augmentation.generateWhereIT(relationField, ctx)?.let {
                        fields += inputValue(relationField.fieldName, it.asType())
                    }
                }
            }
    }
}
