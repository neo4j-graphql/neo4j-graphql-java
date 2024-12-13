package org.neo4j.graphql.schema.model.inputs.options

import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Entity
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.ImplementingTypeAnnotations
import org.neo4j.graphql.domain.directives.LimitDirective
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.utils.PagingUtils

data class OptionsInput<SORT>(
    val limit: Int? = null,
    val offset: Int? = null,
    val sort: List<SORT> = emptyList(),
) {

    fun merge(implementingType: ImplementingType?): OptionsInput<SORT> = merge(implementingType?.annotations)

    fun merge(annotations: ImplementingTypeAnnotations?): OptionsInput<SORT> = merge(annotations?.limit)

    fun merge(limitDirective: LimitDirective?): OptionsInput<SORT> {
        if (limitDirective == null) {
            return this
        }
        val newLimit = when {
            limit != null -> {
                val max = limitDirective.max
                if (max != null && limit > max) {
                    max
                } else {
                    limit
                }
            }

            else -> limitDirective.default ?: limitDirective.max
        }
        return copy(limit = newLimit)
    }

    fun isEmpty(): Boolean = sort.isEmpty() && limit == null && offset == null

    companion object {

        fun create(fieldContainer: FieldContainer<*>?, any: Any?) = any?.toDict()
            ?.let { dict ->
                create(
                    dict, sortFactory = if (fieldContainer == null) {
                        null
                    } else {
                        // A lambda that takes a Dict and returns a SortInput
                        { SortInput.create(fieldContainer, it) }
                    }
                )
            }
            ?: OptionsInput()

        fun <T> create(
            map: Dict,
            limitName: String = Constants.LIMIT,
            offsetName: String = Constants.OFFSET,
            sortName: String = Constants.SORT,
            sortFactory: ((Dict) -> T)?,
        ) = OptionsInput(
            map.nestedObject(limitName) as? Int,
            when (offsetName) {
                Constants.AFTER -> (map.nestedObject(offsetName) as? String)
                    ?.let { PagingUtils.getOffsetFromCursor(it) }
                    ?.let { it + 1 }
                else -> map.nestedObject(offsetName) as? Int
            },
            if (sortFactory == null) emptyList() else map.nestedDictList(sortName).map { sortFactory(it) }
        )
    }

    object Augmentation : AugmentationBase {

        fun generateOptionsIT(implementingType: ImplementingType, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType(implementingType.namings.optionsInputTypeName) { fields, _ ->
                fields += inputValue(Constants.LIMIT, Constants.Types.Int)
                fields += inputValue(Constants.OFFSET, Constants.Types.Int)
                SortInput.Companion.Augmentation
                    .generateSortIT(implementingType, ctx)
                    ?.let {
                        fields += inputValue(
                            Constants.SORT,
                            // TODO make all required, this is only for api alignment
                            it.asType(required = implementingType is Node).List
                        ) {
                            description("Specify one or more ${implementingType.name}Sort objects to sort ${implementingType.pascalCasePlural} by. The sorts will be applied in the order in which they are arranged in the array.".asDescription())
                        }
                    }
            } ?: throw IllegalStateException("at least the paging fields should be present")

        fun generateOptionsIT(entity: Entity, ctx: AugmentationContext): String = entity.extractOnTarget(
            { generateOptionsIT(it, ctx) },
            { Constants.Types.QueryOptions.name }
        )
    }
}
