package org.neo4j.graphql.schema.model.inputs.options

import graphql.language.InputValueDefinition
import org.neo4j.graphql.Constants
import org.neo4j.graphql.List
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.domain.Entity
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.directives.ImplementingTypeAnnotations
import org.neo4j.graphql.domain.directives.LimitDirective
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.toDict
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

        fun generateOptionsArguments(
            entity: Entity,
            ctx: AugmentationContext
        ): List<InputValueDefinition> {
            val arguments = mutableListOf<InputValueDefinition>()

            arguments += inputValue(Constants.LIMIT, Constants.Types.Int)
            arguments += inputValue(Constants.OFFSET, Constants.Types.Int)

            if (entity is ImplementingType) {
                SortInput.Companion.Augmentation
                    .generateSortIT(entity, ctx)
                    ?.let {
                        arguments += inputValue(Constants.SORT, it.asRequiredType().List) {
                            // TODO add in typescript
                            // description("Specify one or more ${entity.name}Sort objects to sort ${entity.pascalCasePlural} by. The sorts will be applied in the order in which they are arranged in the array.".asDescription())
                        }
                    }
            }
            return arguments
        }

    }
}
