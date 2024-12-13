package org.neo4j.graphql.schema.model.outputs.root_connection

import org.neo4j.graphql.Constants
import org.neo4j.graphql.List
import org.neo4j.graphql.NonNull
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.outputs.PageInfoSelection

class RootNodeConnectionSelection {

    object Augmentation : AugmentationBase {
        fun generateImplementingTypeConnectionOT(implementingType: ImplementingType, ctx: AugmentationContext): String {

            val name = implementingType.namings.rootTypeSelection.connection

            return ctx.getOrCreateObjectType(name) { fields, _ ->

                RootNodeConnectionEdgeSelection.Augmentation
                    .generateImplementingTypeEdgeOT(implementingType, ctx)
                    .let { fields += field(Constants.EDGES_FIELD, it.NonNull.List.NonNull) }

                fields += field(Constants.TOTAL_COUNT, Constants.Types.Int.NonNull)
                fields += field(Constants.PAGE_INFO, PageInfoSelection.Augmentation.generateNodeSelection(ctx).NonNull)
            }
                ?: throw IllegalStateException("Expected $name to have fields")
        }
    }
}
