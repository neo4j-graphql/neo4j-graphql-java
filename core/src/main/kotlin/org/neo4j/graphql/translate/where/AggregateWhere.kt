package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.and
import org.neo4j.graphql.asCypherLiteral
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.aggregation.AggregateInput
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

        val (projection, condition) = aggregateWhere(input, node, aggregationTarget, cypherRelation)

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
        node: Node,
        aggregationTarget: Node,
        cypherRelation: Relationship
    ): Pair<List<Expression>, Condition?> {

        var condition = Conditions.noCondition()
        val projections = mutableListOf<Expression>()
        input.countPredicates.forEach {

            val operationVar = queryContext.getNextVariable()
            val param = queryContext.getNextParam(it.value)

            projections += it.createCondition(Functions.count(aggregationTarget), param).`as`(operationVar)
            condition = condition and operationVar.eq(true.asCypherLiteral())
        }

        if (input.node != null) {
            TODO("continue here")
        }

        if (input.edge != null) {
            TODO("continue here")
        }

        return projections to condition
    }
}
