package org.neo4j.graphql.schema.model.outputs

import org.neo4j.graphql.*
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext

class NodeConnectionFieldSelection {


    object Augmentation : AugmentationBase {

        fun generateConnectionOT(field: ConnectionField, ctx: AugmentationContext) =
            ctx.getOrCreateObjectType(field.relationshipField.namings.connectionFieldTypename) { fields, _ ->

                NodeConnectionEdgeFieldSelection.Augmentation
                    // TODO should we use this instead?
                    .generateRelationshipSelection(field.interfaceField as? ConnectionField ?: field, ctx)
//                    .generateRelationshipSelection(field, ctx)
                    .let { fields += field(Constants.EDGES_FIELD, it.asRequiredType().List.NonNull) }

                fields += field(Constants.TOTAL_COUNT, Constants.Types.Int.NonNull)
                fields += field(Constants.PAGE_INFO, Constants.Types.PageInfo.NonNull)
            }
                ?: throw IllegalStateException("Expected ${field.type.name()} to have fields")

    }
}
