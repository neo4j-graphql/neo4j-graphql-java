package org.neo4j.graphql.schema.model.outputs.root_connection

import graphql.language.ListType
import graphql.language.NonNullType
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.capitalize
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext

class RootNodeConnectionSelection {

    object Augmentation : AugmentationBase {
        fun generateImplementingTypeConnectionOT(implementingType: ImplementingType, ctx: AugmentationContext): String {
            // TODO move to names
            val name = "${implementingType.plural.capitalize()}${Constants.OutputTypeSuffix.Connection}"

            return ctx.getOrCreateObjectType(name) { fields, _ ->

                RootNodeConnectionEdgeSelection.Augmentation
                    .generateImplementingTypeEdgeOT(implementingType, ctx)
                    .let { fields += field(Constants.EDGES_FIELD, NonNullType(ListType(it.asRequiredType()))) }

                fields += field(Constants.TOTAL_COUNT, NonNullType(Constants.Types.Int))
                fields += field(Constants.PAGE_INFO, NonNullType(Constants.Types.PageInfo))
            }
                ?: throw IllegalStateException("Expected $name to have fields")
        }
    }
}
