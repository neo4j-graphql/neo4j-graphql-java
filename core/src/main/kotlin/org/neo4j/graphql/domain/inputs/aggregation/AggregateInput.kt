package org.neo4j.graphql.domain.inputs.aggregation

import org.neo4j.graphql.Constants
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

}
