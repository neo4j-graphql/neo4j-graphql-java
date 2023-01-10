package org.neo4j.graphql.domain.inputs.aggregation

import graphql.language.ListType
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.NestedWhere
import org.neo4j.graphql.domain.predicates.ExpressionPredicate
import org.neo4j.graphql.domain.predicates.FieldOperator

class AggregateInput(node: Node, properties: RelationshipProperties?, data: Dict) : NestedWhere<AggregateInput>(
    data,
    { AggregateInput(node, properties, it) }) {

    val countPredicates = COUNT_PREDICATES
        .mapNotNull { (key, op) ->
            val value = data[key] as? Number ?: return@mapNotNull null
            ExpressionPredicate(key, op.conditionCreator, value)
        }

    val node = data[Constants.NODE_FIELD]?.let { AggregationWhereInput(node, Dict(it)) }

    val edge =
        properties?.let { props -> data[Constants.EDGE_FIELD]?.let { AggregationWhereInput(props, Dict(it)) } }

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

        fun create(field: RelationField, value: Any?): AggregateInput? {
            if (value == null) {
                return null
            }
            return field.extractOnTarget(
                onNode = { AggregateInput(it, field.properties, Dict(value)) },
                onInterface = { error("interfaces are not supported for aggregation") },
                onUnion = { error("unions are not supported for aggregation") },
            )
        }
    }

    object Augmentation {
        fun generateAggregateInputIT(sourceName: String, rel: RelationField, ctx: AugmentationContext) =
            ctx.getOrCreateInputObjectType("${sourceName}${rel.fieldName.capitalize()}${Constants.InputTypeSuffix.AggregateInput}") { fields, name ->

                fields += ctx.inputValue(Constants.COUNT, Constants.Types.Int)
                fields += ctx.inputValue(Constants.COUNT + "_LT", Constants.Types.Int)
                fields += ctx.inputValue(Constants.COUNT + "_LTE", Constants.Types.Int)
                fields += ctx.inputValue(Constants.COUNT + "_GT", Constants.Types.Int)
                fields += ctx.inputValue(Constants.COUNT + "_GTE", Constants.Types.Int)

                AggregationWhereInput.Augmentation
                    .generateWhereAggregationInputTypeForContainer(
                        "${sourceName}${rel.fieldName.capitalize()}${Constants.InputTypeSuffix.NodeAggregationWhereInput}",
                        rel.node?.fields,
                        ctx
                    )
                    ?.let { fields += ctx.inputValue(Constants.NODE_FIELD, it.asType()) }

                AggregationWhereInput.Augmentation
                    .generateWhereAggregationInputTypeForContainer(
                        "${sourceName}${rel.fieldName.capitalize()}${Constants.InputTypeSuffix.EdgeAggregationWhereInput}",
                        rel.properties?.fields,
                        ctx
                    )
                    ?.let { fields += ctx.inputValue(Constants.EDGE_FIELD, it.asType()) }

                fields += ctx.inputValue(Constants.AND, ListType(name.asRequiredType()))
                fields += ctx.inputValue(Constants.OR, ListType(name.asRequiredType()))
            }
                ?: throw IllegalStateException("Expected at least the count field")
    }

}
