package org.neo4j.graphql.schema.model.outputs

import org.neo4j.graphql.Constants
import org.neo4j.graphql.NonNull
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext

data object NodeSelection : FieldContainerSelection() {

    object Augmentation : AugmentationBase {

        fun generateNodeSelection(node: Node, ctx: AugmentationContext) = ctx.getOrCreateObjectType(node.name,
            init = {
                description(node.description)
                comments(node.comments)
                directives(node.annotations.otherDirectives)
                implementz(node.interfaces.map { it.name.asType() })
            },
            initFields = { fields, _ ->
                node.fields.forEach { field ->
                    if (field.annotations.selectable?.onRead != false) {
                        fields += FieldContainerSelection.Augmentation.mapField(field, ctx)
                    }
                    if (field.annotations.relayId != null) {
                        fields += field(Constants.ID_FIELD, Constants.Types.ID.NonNull)
                    }
                }
            }
        )
    }
}
