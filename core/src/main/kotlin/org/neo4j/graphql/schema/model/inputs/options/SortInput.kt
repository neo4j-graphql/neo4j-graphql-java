package org.neo4j.graphql.schema.model.inputs.options

import org.neo4j.cypherdsl.core.SortItem.Direction
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asDescription
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict

class SortInput private constructor(entries: Map<String, Direction>) : Map<String, Direction> by entries {
    companion object {
        fun create(data: Dict): SortInput {
            val entries = data.entries
                .mapNotNull { (k, v) ->
                    val key = k as? String ?: return@mapNotNull null
                    val sort = (v as? String)?.let { Direction.valueOf(it) } ?: return@mapNotNull null
                    key to sort
                }
                .toMap()
            return SortInput(entries)
        }

        object Augmentation : AugmentationBase {

            fun generateSortIT(implementingType: ImplementingType, ctx: AugmentationContext) =
                ctx.getOrCreateInputObjectType("${implementingType.name}${Constants.InputTypeSuffix.Sort}",
                    init = { description("Fields to sort ${implementingType.pascalCasePlural} by. The order in which sorts are applied is not guaranteed when specifying many fields in one ${implementingType.name}Sort object.".asDescription()) },
                    initFields = { fields, _ ->
                        implementingType.sortableFields.forEach { field ->
                            fields += inputValue(
                                field.fieldName,
                                Constants.Types.SortDirection
                            ) {
                                field.deprecatedDirective?.let { directive(it) }
                            }
                        }
                    })
        }
    }
}
