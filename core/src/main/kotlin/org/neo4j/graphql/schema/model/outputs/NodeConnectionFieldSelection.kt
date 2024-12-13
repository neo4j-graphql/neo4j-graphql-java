package org.neo4j.graphql.schema.model.outputs

import org.neo4j.graphql.*
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.utils.IResolveTree

class NodeConnectionFieldSelection(
    selection: IResolveTree,
    val edges: List<NodeConnectionEdgeFieldSelection>,
    val pageInfo: List<PageInfoSelection>,
    val totalCount: List<IResolveTree>,
) : BaseSelection<NodeConnectionFieldSelection>(selection) {


    object Augmentation : AugmentationBase {

        fun generateConnectionOT(field: ConnectionField, ctx: AugmentationContext) =
            ctx.getOrCreateObjectType(field.relationshipField.namings.connectionFieldTypename) { fields, _ ->

                NodeConnectionEdgeFieldSelection.Augmentation
                    // TODO should we use this instead?
                    .generateRelationshipSelection(field.interfaceField as? ConnectionField ?: field, ctx)
//                    .generateRelationshipSelection(field, ctx)
                    .let { fields += field(NodeConnectionFieldSelection::edges, it.asRequiredType().List.NonNull) }

                fields += field(NodeConnectionFieldSelection::totalCount, Constants.Types.Int.NonNull)
                fields += field(
                    NodeConnectionFieldSelection::pageInfo,
                    PageInfoSelection.Augmentation.generateNodeSelection(ctx).NonNull
                )
            }
                ?: throw IllegalStateException("Expected ${field.type.name()} to have fields")

    }

    companion object {

        fun parse(field: ConnectionField, selection: IResolveTree): NodeConnectionFieldSelection {
            val typeName = field.relationshipField.namings.connectionFieldTypename
            return NodeConnectionFieldSelection(
                selection,
                selection.getFieldOfType(
                    typeName,
                    NodeConnectionFieldSelection::edges
                ) { NodeConnectionEdgeFieldSelection.parse(field, it) },
                selection.getFieldOfType(
                    typeName,
                    NodeConnectionFieldSelection::pageInfo
                ) { PageInfoSelection.parse(it) },
                selection.getFieldOfType(typeName, NodeConnectionFieldSelection::totalCount),
            )
        }
    }
}
