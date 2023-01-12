package org.neo4j.graphql.schema.model.outputs

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.makeRequired
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext

class NodeConnectionEdgeFieldSelection : FieldContainerSelection() {

    object Augmentation : AugmentationBase {

        fun generateRelationshipSelection(field: ConnectionField, ctx: AugmentationContext): String =
            ctx.getOrCreateObjectType(field.relationshipTypeName,
                init = { field.properties?.let { implementz(it.interfaceName.asType()) } },
                initFields = { fields, _ ->

                    fields += field(Constants.CURSOR_FIELD, Constants.Types.String.makeRequired())

                    field.relationshipField.extractOnTarget(
                        onNode = { NodeSelection.Augmentation.generateNodeSelection(it, ctx) },
                        onInterface = { InterfaceSelection.Augmentation.generateInterfaceSelection(it, ctx) },
                        onUnion = { it.name }
                    )?.let {
                        fields += field(Constants.NODE_FIELD, it.asType(required = true))
                    }

                    field.properties?.fields?.forEach {
                        fields += FieldContainerSelection.Augmentation.mapField(it, ctx)
                    }
                })
                ?: throw IllegalStateException("Expected ${field.relationshipTypeName} to have fields")

    }
}
