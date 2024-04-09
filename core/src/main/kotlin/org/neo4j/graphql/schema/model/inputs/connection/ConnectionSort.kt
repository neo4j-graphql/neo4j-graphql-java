package org.neo4j.graphql.schema.model.inputs.connection

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.options.SortInput

class ConnectionSort(data: Dict) {

    val node = data.nestedDict(Constants.NODE_FIELD)?.let { SortInput.create(it) }

    val edge = data.nestedDict(Constants.EDGE_FIELD)?.let { SortInput.create(it) }

    object Augmentation : AugmentationBase {

        fun generateConnectionSortIT(field: ConnectionField, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType(field.relationshipField.namings.connectionSortInputTypename) { fields, _ ->

                generatePropertySortIT(field.interfaceField as? ConnectionField ?: field, ctx)
                    ?.let { fields += inputValue(Constants.EDGE_FIELD, it) }

                field.relationshipField.implementingType
                    ?.let { SortInput.Companion.Augmentation.generateSortIT(it, ctx) }
                    ?.let { fields += inputValue(Constants.NODE_FIELD, it.asType()) }
            }

        private fun generatePropertySortIT(field: ConnectionField, ctx: AugmentationContext) = ctx.getEdgeInputField(
            field.relationshipField,
            { it.namings.sortInputTypeName }) {
            ctx.getOrCreateInputObjectType(it.namings.sortInputTypeName) { fields, _ ->
                it.properties?.fields?.forEach {
                    fields += inputValue(it.fieldName, Constants.Types.SortDirection)
                }
            }
        }
    }
}
