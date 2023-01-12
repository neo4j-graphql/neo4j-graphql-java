package org.neo4j.graphql.schema.model.inputs.aggregation

import graphql.language.ListType
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asRequiredType
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.PrimitiveField
import org.neo4j.graphql.domain.predicates.AggregationFieldPredicate
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.NestedWhere

class AggregationWhereInput(
    fieldContainer: FieldContainer<*>,
    data: Dict
) : NestedWhere<AggregationWhereInput>(
    data,
    { nestedData: Dict -> AggregationWhereInput(fieldContainer, nestedData) }) {

    val predicates = data
        .filterNot { SPECIAL_WHERE_KEYS.contains(it.key) }
        .mapNotNull { (key, value) ->
            fieldContainer.aggregationPredicates[key]
                ?.let { def -> AggregationFieldPredicate(def, value) }
        }

    object Augmentation : AugmentationBase {

        fun generateWhereAggregationInputTypeForContainer(
            name: String,
            relFields: List<BaseField>?,
            ctx: AugmentationContext
        ) =
            ctx.getOrCreateInputObjectType(name) { fields, _ ->
                relFields
                    ?.filterIsInstance<PrimitiveField>()
                    ?.flatMap { it.aggregationPredicates.entries }
                    ?.forEach { (name, def) ->
                        fields += inputValue(name, def.type) {
                            def.field.deprecatedDirective?.let { directive(it) }
                        }
                    }

                if (fields.isNotEmpty()) {
                    fields += inputValue(Constants.AND, ListType(name.asRequiredType()))
                    fields += inputValue(Constants.OR, ListType(name.asRequiredType()))
                }
            }
    }
}
