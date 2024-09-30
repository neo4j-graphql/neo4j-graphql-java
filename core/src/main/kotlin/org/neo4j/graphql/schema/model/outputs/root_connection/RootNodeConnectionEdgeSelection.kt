package org.neo4j.graphql.schema.model.outputs.root_connection

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.makeRequired
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.outputs.InterfaceSelection
import org.neo4j.graphql.schema.model.outputs.NodeSelection

class RootNodeConnectionEdgeSelection {

    object Augmentation : AugmentationBase {
        fun generateImplementingTypeEdgeOT(implementingType: ImplementingType, ctx: AugmentationContext): String =
            ctx.getOrCreateObjectType(implementingType.namings.rootTypeSelection.edge) { fields, _ ->

                fields += field(Constants.CURSOR_FIELD, Constants.Types.String.makeRequired())

                implementingType.extractOnImplementingType(
                    onNode = { NodeSelection.Augmentation.generateNodeSelection(it, ctx) },
                    onInterface = { InterfaceSelection.Augmentation.generateInterfaceSelection(it, ctx) }

                )
                    ?.let { fields += field(Constants.NODE_FIELD, it.asType(true)) }

            }
                ?: throw IllegalStateException("Expected ${implementingType.namings.rootTypeSelection.edge} to have fields")
    }
}
