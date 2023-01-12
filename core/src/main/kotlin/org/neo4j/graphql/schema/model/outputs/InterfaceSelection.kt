package org.neo4j.graphql.schema.model.outputs

import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.schema.AugmentationContext

class InterfaceSelection : FieldContainerSelection() {

    object Augmentation {

        fun generateInterfaceSelection(interfaze: Interface, ctx: AugmentationContext) =
            ctx.getOrCreateInterfaceType(interfaze.name,
                init = {
                    description(interfaze.description)
                    comments(interfaze.comments)
                    directives(interfaze.otherDirectives)
                    implementz(interfaze.interfaces.map { it.name.asType() })
                },
                initFields = { fields, _ ->
                    interfaze.fields.filterNot { it.writeonly }
                        .forEach { fields += FieldContainerSelection.Augmentation.mapField(it, ctx) }
                })
    }
}
