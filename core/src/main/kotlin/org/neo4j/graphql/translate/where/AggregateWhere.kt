package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.fields.HasCoalesceValue
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.predicates.AggregationFieldPredicate
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.aggregation.AggregateInput
import org.neo4j.graphql.schema.model.inputs.aggregation.AggregationWhereInput
import org.neo4j.graphql.translate.WhereResult

class AggregateWhere(val schemaConfig: SchemaConfig, val queryContext: QueryContext) {

    fun createAggregateWhere(
        chainStr: ChainString,
        field: RelationBaseField,
        node: Node,
        input: AggregateInput
    ): WhereResult {
        val (aggregationTarget, additionalConditions) = field.extractOnTarget(
            onNode = {
                it.asCypherNode(queryContext).named(queryContext.getNextVariable(chainStr)) to Cypher.noCondition()
            },
            onInterface = {
                val target = Cypher.anyNode(queryContext.getNextVariable(chainStr))
                target to it.implementations.values
                    .map { target.hasLabels(*it.allLabels(queryContext).toTypedArray()) }
                    .foldWithOr()
            },
            onUnion = { error("unions are not supported for aggregation") }
        )

        // TODO cleanup relName is taken before name of aggregationTarget b/c of js naming
        val relName = queryContext.getNextVariable(chainStr)

        if (field is RelationField) {

            val cypherRelation = field.createDslRelation(node, aggregationTarget).named(relName)

            val condition = aggregateWhere(input, aggregationTarget, cypherRelation)
                ?: return WhereResult.EMPTY

            val operationVar = queryContext.getNextVariable()
            return WhereResult(
                operationVar.eq(true.asCypherLiteral()), listOf(
                    Cypher
                        .with(node)
                        .match(cypherRelation)
                        .optionalWhere(additionalConditions)
                        .returning(condition.`as`(operationVar))
                        .build()
                )
            )
        } else {
            TODO()
        }
    }

    private fun aggregateWhere(
        input: AggregateInput,
        aggregationTarget: Node,
        cypherRelation: Relationship
    ): Condition? {

        var condition: Condition? = null
        input.countPredicates.forEach {
            val param = queryContext.getNextParam(it.value)
            condition = condition and it.createCondition(Cypher.count(aggregationTarget), param)
        }

        input.reduceNestedConditions { _, _, nested ->
            aggregateWhere(nested, aggregationTarget, cypherRelation)
        }?.let {
            condition = condition and it
        }

        input.node?.let { createAggregationWhere(aggregationTarget, it) }?.let {
            condition = condition and it
        }

        input.edge?.let { createAggregationWhere(cypherRelation, it) }?.let {
            condition = condition and it
        }

        return condition
    }


    private fun createAggregationWhere(
        propertyContainer: PropertyContainer,
        whereInput: AggregationWhereInput
    ): Condition? {
        var result = whereInput.reduceNestedConditions { _, _, nested ->
            createAggregationWhere(propertyContainer, nested)
        }

        whereInput.predicates.forEach {
            result = result and resolveAggregationCondition(propertyContainer, it)
        }

        return result
    }

    private fun resolveAggregationCondition(
        propertyContainer: PropertyContainer,
        predicate: AggregationFieldPredicate
    ): Condition {
        val field = predicate.field
        val dbProperty = propertyContainer.property(field.dbPropertyName)
        val property =
            (field as? HasCoalesceValue)
                ?.coalesceValue
                ?.toJavaValue()
                ?.let { Cypher.coalesce(dbProperty, it.asCypherLiteral()) }
                ?: dbProperty
        val rhs: Expression = queryContext
            .getNextParam(predicate.value)
            .let { field.convertInputToCypher(it) }
        return predicate.resolver.createCondition(property, rhs)
    }
}
