package org.neo4j.graphql.schema.model.outputs.root_connection

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.makeRequired
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.outputs.NodeSelection

class RootNodeConnectionEdgeSelection {

    object Augmentation : AugmentationBase {
        fun generateNodeEdgeOT(node: Node, ctx: AugmentationContext): String =
            ctx.getOrCreateObjectType("${node.name}${Constants.OutputTypeSuffix.Edge}") { fields, _ ->

                fields += field(Constants.CURSOR_FIELD, Constants.Types.String.makeRequired())

                NodeSelection.Augmentation
                    .generateNodeSelection(node, ctx)
                    ?.let { fields += field(Constants.NODE_FIELD, it.asType(true)) }

            }
                ?: throw IllegalStateException("Expected ${node.name}${Constants.OutputTypeSuffix.Edge} to have fields")
    }
}
