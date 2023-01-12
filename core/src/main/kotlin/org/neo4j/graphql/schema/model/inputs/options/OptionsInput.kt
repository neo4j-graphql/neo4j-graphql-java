package org.neo4j.graphql.schema.model.inputs.options

import graphql.language.ListType
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.cypherdsl.core.SortItem
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asDescription
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.directives.QueryOptionsDirective
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.toDict

data class OptionsInput(
    val limit: Int? = null,
    val offset: Int? = null,
    val sort: List<Map<String, SortItem.Direction>>? = null,
) {
    fun merge(queryOptions: QueryOptionsDirective?): OptionsInput {
        if (queryOptions == null) {
            return this
        }
        val newLimit = when {
            limit != null -> {
                val max = queryOptions.limit?.max
                if (max != null && limit > max) {
                    max
                } else {
                    limit
                }
            }

            else -> queryOptions.limit?.default ?: queryOptions.limit?.max
        }
        return copy(limit = newLimit)
    }

    fun wrapLimitAndOffset(expression: Expression): Expression {
        if (offset != null && limit == null) {
            return Cypher.subListFrom(expression, offset)
        }

        if (limit != null && offset == null) {
            return Cypher.subListUntil(expression, limit)
        }
        if (offset != null && limit != null) {
            return Cypher.subList(expression, offset, offset + limit)
        }
        return expression
    }

    companion object {

        fun create(any: Any?) = any?.toDict()?.let { create(it) } ?: OptionsInput()

        fun create(map: Dict) = OptionsInput(
            map.nestedObject(Constants.LIMIT) as? Int,
            map.nestedObject(Constants.OFFSET) as? Int,
            map.nestedDictList(Constants.SORT).map { SortInput.create(it) }.takeIf { it.isNotEmpty() }
        )
    }

    object Augmentation : AugmentationBase {

        fun generateOptionsIT(implementingType: ImplementingType, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType("${implementingType.name}${Constants.InputTypeSuffix.Options}") { fields, _ ->
                fields += inputValue(Constants.LIMIT, Constants.Types.Int)
                fields += inputValue(Constants.OFFSET, Constants.Types.Int)
                SortInput.Companion.Augmentation
                    .generateSortIT(implementingType, ctx)
                    ?.let {
                        fields += inputValue(
                            Constants.SORT,
                            ListType(it.asRequiredType())
                        ) {
                            description("Specify one or more ${implementingType.name}Sort objects to sort ${implementingType.pascalCasePlural} by. The sorts will be applied in the order in which they are arranged in the array.".asDescription())
                        }
                    }
            } ?: throw IllegalStateException("at least the paging fields should be present")
    }
}
