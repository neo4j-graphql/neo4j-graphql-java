package org.neo4j.graphql.schema.model.outputs

import graphql.language.ListType
import graphql.language.NonNullType
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.name
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
                    .let { fields += field(Constants.EDGES_FIELD, NonNullType(ListType(it.asRequiredType()))) }

                fields += field(Constants.TOTAL_COUNT, NonNullType(Constants.Types.Int))
                fields += field(Constants.PAGE_INFO, NonNullType(Constants.Types.PageInfo))
            }
                ?: throw IllegalStateException("Expected ${field.typeMeta.type.name()} to have fields")

    }
}
