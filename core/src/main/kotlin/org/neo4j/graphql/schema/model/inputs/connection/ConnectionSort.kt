package org.neo4j.graphql.schema.model.inputs.connection

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.options.SortInput

class ConnectionSort(field: ConnectionField, data: Dict) {

    val node = data.nestedDict(Constants.NODE_FIELD)
        ?.let { sort -> field.relationshipField.implementingType?.let { SortInput.create(it, sort) } }

    val edge = data.nestedDict(Constants.EDGE_FIELD)
        ?.let { sort -> field.properties?.let { SortInput.create(it, sort) } }


    object Augmentation : AugmentationBase {

        fun generateConnectionSortIT(field: ConnectionField, ctx: AugmentationContext): String? {
            val relationshipField = field.relationshipField
            return ctx.getOrCreateInputObjectType(relationshipField.namings.connectionSortInputTypename) { fields, _ ->

                generatePropertySortIT(field.interfaceField as? ConnectionField ?: field, ctx)
                    ?.let { fields += inputValue(Constants.EDGE_FIELD, it) }

                relationshipField.implementingType
                    ?.let { SortInput.Companion.Augmentation.generateSortIT(it, ctx) }
                    ?.let { fields += inputValue(Constants.NODE_FIELD, it.asType()) }
            }
        }

        private fun generatePropertySortIT(field: ConnectionField, ctx: AugmentationContext) = ctx.getEdgeInputField(
            field.relationshipField
        ) { edgeField ->
            ctx.getOrCreateInputObjectType(edgeField.namings.sortInputTypeName) { fields, _ ->
                edgeField.properties?.fields?.forEach {
                    fields += inputValue(it.fieldName, Constants.Types.SortDirection)
                }
            }
        }
    }
}
