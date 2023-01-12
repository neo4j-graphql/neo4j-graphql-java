package org.neo4j.graphql.schema.model.inputs.connect_or_create

import graphql.language.ListType
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.ScalarProperties

class ConnectOrCreateWhere(node: Node, data: Dict) {

    val node = data[Constants.NODE_FIELD]?.let {
        // UniqueWhere
        ScalarProperties.create(data, node)
    }

    object Augmentation : AugmentationBase {
        fun generateConnectOrCreateWhereIT(node: Node, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType("${node.name}${Constants.InputTypeSuffix.ConnectOrCreateWhere}") { fields, _ ->

                generateUniqueWhereIT(node, ctx)
                    ?.let { fields += inputValue(Constants.NODE_FIELD, it.asRequiredType()) }
            }

        private fun generateUniqueWhereIT(node: Node, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType("${node.name}${Constants.InputTypeSuffix.UniqueWhere}") { fields, _ ->
                node.uniqueFields.forEach { uniqueField ->
                    val type = if (uniqueField.typeMeta.type.isList()) {
                        ListType(uniqueField.typeMeta.type.inner())
                    } else {
                        uniqueField.typeMeta.type.name().asType()
                    }
                    fields += inputValue(uniqueField.fieldName, type)
                }
            }
    }
}
