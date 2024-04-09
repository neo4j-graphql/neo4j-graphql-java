package org.neo4j.graphql.schema.model.outputs

import org.neo4j.graphql.*
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationDeclarationField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext

class NodeConnectionEdgeFieldSelection : FieldContainerSelection() {

    object Augmentation : AugmentationBase {

        fun generateRelationshipSelection(field: ConnectionField, ctx: AugmentationContext): String =
            ctx.getOrCreateObjectType(field.relationshipField.namings.relationshipFieldTypename2) { fields, _ ->

                fields += field(Constants.CURSOR_FIELD, Constants.Types.String.makeRequired())

                field.relationshipField.extractOnTarget(
                    onNode = { NodeSelection.Augmentation.generateNodeSelection(it, ctx) },
                    onInterface = { InterfaceSelection.Augmentation.generateInterfaceSelection(it, ctx) },
                    onUnion = { it.name }
                )?.let {
                    fields += field(Constants.NODE_FIELD, it.asType(required = true))
                }

                if (field.relationshipField is RelationDeclarationField) {
                    ctx.getOrCreateUnionType(field.relationshipField.namings.relationshipPropertiesFieldTypename) { types, _ ->
                        types += field.relationshipField.relationshipImplementations
                            .mapNotNull { generateEdgeRelationshipSelection(it.properties, ctx) }
                            .distinct()
                            .map { it.asType() }
                    }
                } else {
                    field.properties?.let { generateEdgeRelationshipSelection(it, ctx) }
                }
                    ?.let { fields += field(Constants.PROPERTIES_FIELD, it.asRequiredType()) }
            }
                ?: throw IllegalStateException("Expected ${field.relationshipField.namings.relationshipFieldTypename2} to have fields")

        private fun generateEdgeRelationshipSelection(
            properties: RelationshipProperties?,
            ctx: AugmentationContext
        ): String? {
            if (properties == null) {
                return null
            }
            return ctx.getOrCreateObjectType(
                properties.typeName,
                init = {
                    description(
                        "The edge properties for the following fields:\n${
                            properties.usedByRelations.joinToString("\n") { "* ${it.getOwnerName()}.${it.fieldName}" }
                        }"
                            .asDescription()
                    )
                },
                initFields = { fields, _ ->
                    properties.fields.forEach {
                        fields += FieldContainerSelection.Augmentation.mapField(it, ctx)
                    }
                })
        }

    }
}
