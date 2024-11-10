package org.neo4j.graphql.schema.model.outputs

import org.neo4j.graphql.Constants
import org.neo4j.graphql.NonNull
import org.neo4j.graphql.asDescription
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.utils.IResolveTree

class NodeConnectionEdgeFieldSelection(
    selection: IResolveTree,
    val cursor: List<IResolveTree>,
    val node: List<IResolveTree>,
    val properties: List<IResolveTree>,
) : BaseSelection<NodeConnectionFieldSelection>(selection), FieldContainerSelection {

    object Augmentation : AugmentationBase {

        fun generateRelationshipSelection(field: ConnectionField, ctx: AugmentationContext): String =
            ctx.getOrCreateObjectType(field.relationshipField.namings.relationshipFieldTypename) { fields, _ ->

                fields += field(NodeConnectionEdgeFieldSelection::cursor, Constants.Types.String.NonNull)

                field.relationshipField.extractOnTarget(
                    onNode = { NodeSelection.Augmentation.generateNodeSelection(it, ctx) },
                    onInterface = { InterfaceSelection.Augmentation.generateInterfaceSelection(it, ctx) },
                    onUnion = { it.name }
                )?.let {
                    fields += field(NodeConnectionEdgeFieldSelection::node, it.asRequiredType())
                }

                field.properties?.let { generateEdgeRelationshipSelection(it, ctx) }
                    ?.let { fields += field(NodeConnectionEdgeFieldSelection::properties, it.asRequiredType()) }
            }
                ?: throw IllegalStateException("Expected ${field.relationshipField.namings.relationshipFieldTypename} to have fields")

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

    companion object {

        fun parse(field: ConnectionField, selection: IResolveTree): NodeConnectionEdgeFieldSelection {
            val typeName = field.relationshipField.namings.relationshipFieldTypename
            return NodeConnectionEdgeFieldSelection(
                selection,
                selection.getFieldOfType(typeName, NodeConnectionEdgeFieldSelection::cursor),
                selection.getFieldOfType(typeName, NodeConnectionEdgeFieldSelection::node),
                selection.getFieldOfType(typeName, NodeConnectionEdgeFieldSelection::properties),
            )
        }
    }
}
