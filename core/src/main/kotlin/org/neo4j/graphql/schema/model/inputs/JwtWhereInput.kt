package org.neo4j.graphql.schema.model.inputs

import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.predicates.ScalarFieldPredicate

class JwtWhereInput(
    fieldContainer: FieldContainer<BaseField>,
    data: Dict
) :
    WhereInput.FieldContainerWhereInput<JwtWhereInput>(data, fieldContainer, { JwtWhereInput(fieldContainer, it) }) {


    fun filterByValues(
        receivedValues: Map<String, Any?>,
        jwtClaims: Map<String, String>? = null
    ): Boolean {

        val result = evaluateNestedConditions { filterByValues(receivedValues, jwtClaims) }
        if (!result) return false

        predicates.forEach { predicate ->
            if (predicate !is ScalarFieldPredicate) {
                error("Only ScalarFieldPredicate is supported")
            }

            val receivedValue = getReceivedValue(predicate.name, receivedValues, jwtClaims)
            if (!predicate.evaluate(receivedValue)) {
                return false
            }
        }
        return true
    }

    private fun getReceivedValue(
        fieldName: String,
        receivedValues: Map<String, Any?>,
        jwtClaims: Map<String, String>? = null
    ): Any? {
        val mappedJwtClaim = jwtClaims?.get(fieldName)
        return if (mappedJwtClaim != null) {
            TODO()
//            dotProp.get<T>(receivedValues, mappedJwtClaim)
        } else {
            receivedValues[fieldName]
        }
    }

}
