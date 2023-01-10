package org.neo4j.graphql.domain.inputs.connection

import org.neo4j.graphql.AugmentationContext
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.options.SortInput
import org.neo4j.graphql.name
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation

class ConnectionSort(data: Dict) {

    val node = data[Constants.NODE_FIELD]?.let { SortInput.create(Dict(it)) }

    val edge = data[Constants.EDGE_FIELD]?.let { SortInput.create(Dict(it)) }

    object Augmentation {

        fun generateConnectionSortIT(field: ConnectionField, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType(field.typeMeta.type.name() + Constants.InputTypeSuffix.Sort) { fields, _ ->

                field.relationshipField.properties
                    ?.let { generatePropertySortIT(it, ctx) }
                    ?.let { fields += ctx.inputValue(Constants.EDGE_FIELD, it.asType()) }

                field.relationshipField.getImplementingType()
                    ?.let { SortInput.Companion.Augmentation.generateSortIT(it, ctx) }
                    ?.let { fields += ctx.inputValue(Constants.NODE_FIELD, it.asType()) }
            }

        private fun generatePropertySortIT(properties: RelationshipProperties, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType(properties.interfaceName + Constants.InputTypeSuffix.Sort) { fields, _ ->
                properties.fields.forEach {
                    fields += ctx.inputValue(it.fieldName, Constants.Types.SortDirection)
                }
            }
    }
}
