package org.neo4j.graphql.domain.inputs.aggregation

import graphql.language.ListType
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.PrimitiveField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.NestedWhere
import org.neo4j.graphql.domain.predicates.AggregationFieldPredicate

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

    object Augmentation {

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
                        fields += ctx.inputValue(name, def.type) {
                            directives(def.field.deprecatedDirectives)
                        }
                    }

                if (fields.isNotEmpty()) {
                    fields += ctx.inputValue(Constants.AND, ListType(name.asRequiredType()))
                    fields += ctx.inputValue(Constants.OR, ListType(name.asRequiredType()))
                }
            }
    }
}
