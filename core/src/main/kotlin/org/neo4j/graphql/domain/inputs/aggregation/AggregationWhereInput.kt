package org.neo4j.graphql.domain.inputs.aggregation

import org.neo4j.graphql.domain.FieldContainer
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
}
