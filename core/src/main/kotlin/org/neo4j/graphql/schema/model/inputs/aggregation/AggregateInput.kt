package org.neo4j.graphql.schema.model.inputs.aggregation

import org.neo4j.graphql.Constants
import org.neo4j.graphql.asType
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.domain.predicates.ExpressionPredicate
import org.neo4j.graphql.domain.predicates.FieldOperator
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.NestedWhere
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.toDict

class AggregateInput(node: Node, properties: RelationshipProperties?, data: Dict) : NestedWhere<AggregateInput>(
    data,
    { AggregateInput(node, properties, it) }) {

    //    val countPredicates = COUNT_PREDICATES
//        .mapNotNull { (key, op) ->
//            val value = data[key] as? Number ?: return@mapNotNull null
//            ExpressionPredicate(key, op.conditionCreator, value)
//        }
    // TODO this is just to have the same order as in js, the code above is more concise
    val countPredicates = data.mapNotNull { (key, value) ->
        val numberValue = value as? Number ?: return@mapNotNull null
        COUNT_PREDICATES[key]?.let { op -> ExpressionPredicate(key, op.conditionCreator, numberValue) }
    }

    val node = data.nestedDict(Constants.NODE_FIELD)?.let { AggregationWhereInput(node, it) }

    val edge = properties
        ?.let { props -> data.nestedDict(Constants.EDGE_FIELD)?.let { AggregationWhereInput(props, it) } }

    companion object {

        val COUNT_PREDICATES = listOf(
            FieldOperator.EQUAL,
            FieldOperator.LT,
            FieldOperator.LTE,
            FieldOperator.GT,
            FieldOperator.GTE,
        )
            .map { op -> Constants.COUNT + ("_".takeIf { op.suffix.isNotBlank() } ?: "") + op.suffix to op }
            .toMap()

        fun create(field: RelationBaseField, value: Any?): AggregateInput? {
            if (value == null) {
                return null
            }
            return field.extractOnTarget(
                onNode = { AggregateInput(it, field.properties, value.toDict()) },
                onInterface = { error("interfaces are not supported for aggregation") },
                onUnion = { error("unions are not supported for aggregation") },
            )
        }
    }

    object Augmentation : AugmentationBase {
        fun generateAggregateInputIT(rel: RelationBaseField, ctx: AugmentationContext): String? {
            if (rel.annotations.filterable?.byAggregate == false) {
                return null
            }
            return ctx.getOrCreateInputObjectType(rel.namings.aggregateInputTypeName) { fields, name ->

                fields += inputValue(Constants.COUNT, Constants.Types.Int)
                fields += inputValue(Constants.COUNT + "_LT", Constants.Types.Int)
                fields += inputValue(Constants.COUNT + "_LTE", Constants.Types.Int)
                fields += inputValue(Constants.COUNT + "_GT", Constants.Types.Int)
                fields += inputValue(Constants.COUNT + "_GTE", Constants.Types.Int)

                AggregationWhereInput.Augmentation
                    .generateWhereAggregationInputTypeForContainer(
                        rel.namings.nodeAggregationWhereInputTypeName,
                        rel.node?.fields?.filter { it.isAggregationFilterable() },
                        ctx
                    )
                    ?.let { fields += inputValue(Constants.NODE_FIELD, it.asType()) }

                ctx.getEdgeInputField(
                    rel,
                    { it.namings.edgeAggregationWhereInputTypeName }
                ) {
                    AggregationWhereInput.Augmentation
                        .generateWhereAggregationInputTypeForContainer(
                            it.namings.edgeAggregationWhereInputTypeName,
                            it.properties?.fields,
                            ctx
                        )
                }
                    ?.let { fields += inputValue(Constants.EDGE_FIELD, it) }

                WhereInput.Augmentation.addNestingWhereFields(name, fields)
            }
                ?: throw IllegalStateException("Expected at least the count field")
        }
    }

}
