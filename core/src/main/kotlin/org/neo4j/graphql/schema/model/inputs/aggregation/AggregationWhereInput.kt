package org.neo4j.graphql.schema.model.inputs.aggregation

import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.PrimitiveField
import org.neo4j.graphql.domain.predicates.AggregationFieldPredicate
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.NestedWhere
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.toDeprecatedDirective

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
                            (def.field.deprecatedDirective ?: def.deprecated?.toDeprecatedDirective())?.let {
                                directive(
                                    it
                                )
                            }
                        }
                    }

                WhereInput.Augmentation.addNestingWhereFields(name, fields, ctx)
            }
    }
}
