package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.fields.HasCoalesceValue
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.model.inputs.aggregation.AggregateInput
import org.neo4j.graphql.schema.model.inputs.aggregation.AggregationWhereInput
import org.neo4j.graphql.domain.predicates.AggregationFieldPredicate
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.translate.WhereResult

class AggregateWhere(val schemaConfig: SchemaConfig, val queryContext: QueryContext) {

    fun createAggregateWhere(
        chainStr: ChainString,
        field: RelationField,
        node: Node,
        input: AggregateInput
    ): WhereResult {
        val targetNode = field.node ?: error("expecting node on relation target for aggregation")
        val relName =
            queryContext.getNextVariable(chainStr) // TODO cleanup relName is taken before name of aggregationTarget b/c of js naming
        val aggregationTarget = targetNode.asCypherNode(queryContext).named(queryContext.getNextVariable(chainStr))
        val cypherRelation = field.createDslRelation(node, aggregationTarget).named(relName)

        val (projection, condition) = aggregateWhere(input, aggregationTarget, cypherRelation)

        return WhereResult(
            condition, listOf(
                Cypher
                    .with(node)
                    .match(cypherRelation)
                    .returning(*projection.toTypedArray())
                    .build()
            )
        )
    }

    private fun aggregateWhere(
        input: AggregateInput,
        aggregationTarget: Node,
        cypherRelation: Relationship
    ): Pair<List<Expression>, Condition?> {

        val projections = mutableListOf<Expression>()

        var condition = input.reduceNestedConditions { _, _, nested ->
            val (nestedProjections, nestedCondition) = aggregateWhere(nested, aggregationTarget, cypherRelation)
            projections += nestedProjections
            nestedCondition
        }

        input.countPredicates.forEach {

            val operationVar = queryContext.getNextVariable()
            val param = queryContext.getNextParam(it.value)

            projections += it.createCondition(Functions.count(aggregationTarget), param).`as`(operationVar)
            condition = condition and operationVar.eq(true.asCypherLiteral())
        }

        input.node?.let { createAggregationWhere(aggregationTarget, it) }?.let {
            val operationVar = queryContext.getNextVariable()
            projections += it.`as`(operationVar)
            condition = condition and operationVar.eq(true.asCypherLiteral())
        }

        input.edge?.let { createAggregationWhere(cypherRelation, it) }?.let {
            val operationVar = queryContext.getNextVariable()
            projections += it.`as`(operationVar)
            condition = condition and operationVar.eq(true.asCypherLiteral())
        }

        return projections to condition
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
                ?.let { Functions.coalesce(dbProperty, it.asCypherLiteral()) }
                ?: dbProperty
        var rhs: Expression = queryContext.getNextParam(predicate.value)
        rhs = when (field.typeMeta.type.name()) {
            Constants.DURATION -> Functions.duration(rhs)
            Constants.DATE_TIME -> Functions.datetime(rhs)
            Constants.LOCAL_DATE_TIME -> Functions.localdatetime(rhs)
            Constants.LOCAL_TIME -> Functions.localtime(rhs)
            Constants.DATE -> Functions.date(rhs)
            Constants.TIME -> Functions.time(rhs)
            else -> rhs
        }
        return predicate.resolver.createCondition(property, rhs, queryContext)
    }
}
