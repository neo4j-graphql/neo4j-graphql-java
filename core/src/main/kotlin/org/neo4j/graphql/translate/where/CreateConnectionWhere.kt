package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.PropertyContainer
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.and
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.predicates.ConnectionPredicate
import org.neo4j.graphql.foldWithAnd
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.translate.WhereResult

//TODO completed
fun createConnectionWhere(
    whereInput: ConnectionWhere.ImplementingTypeConnectionWhere<*>?,
    node: Node,
    nodeVariable: PropertyContainer,
    relationship: RelationField,
    relationshipVariable: PropertyContainer,
    parameterPrefix: ChainString,
    schemaConfig: SchemaConfig,
    queryContext: QueryContext,
    usePrefix: PrefixUsage = PrefixUsage.NONE
): WhereResult {

    fun createOnNode(key: String, value: WhereInput): WhereResult {
        val result = mutableListOf<Condition?>()
        val subQueries = mutableListOf<Statement>()
        if (value is WhereInput.FieldContainerWhereInput<*>) {
            if (!value.hasFilterForNode(node)) {
                throw IllegalArgumentException("_on is used as the only argument and node is not present within")
            }

            val (inputOnForNode, inputExcludingOnForNode) = when (value) {
                is WhereInput.InterfaceWhereInput -> value.on?.getDataForNode(node) to value.getCommonFields(node)
                else -> null to value
            }

            val (whereCondition, whereSubquery) = createWhere(
                node,
                inputExcludingOnForNode,
                nodeVariable,
                parameterPrefix,
                schemaConfig,
                queryContext,
                usePrefix,
            )
            result += whereCondition
            subQueries.addAll(whereSubquery)

            if (inputOnForNode != null) {
                val (innerWhereCondition, innerWhereSubquery) = createWhere(
                    node,
                    inputOnForNode,
                    nodeVariable,
                    parameterPrefix,
                    schemaConfig,
                    queryContext,
                    usePrefix,
                )
                result += innerWhereCondition
                subQueries.addAll(innerWhereSubquery)
            }
        }

        return WhereResult(result.foldWithAnd(), subQueries)
    }

    val subQueries = mutableListOf<Statement>()
    var condition = whereInput?.reduceNestedConditions { key, index, nested ->
        val (whereCondition, whereSubquery) = createConnectionWhere(
            nested,
            node,
            nodeVariable,
            relationship,
            relationshipVariable,
            parameterPrefix.extend(key, index),
            schemaConfig,
            queryContext,
            usePrefix,
        )
        subQueries.addAll(whereSubquery)
        whereCondition
    }

    whereInput?.predicates?.forEach { predicate ->
        val (whereCondition, whereSubquery) = when (predicate.target) {
            ConnectionPredicate.Target.EDGE -> createWhere(
                relationship.properties,
                predicate.value,
                relationshipVariable,
                parameterPrefix,
                schemaConfig,
                queryContext,
                usePrefix,
            )

            ConnectionPredicate.Target.NODE -> createOnNode(predicate.key, predicate.value)
        }
        whereCondition?.let { predicate.op.conditionCreator(it) }
            ?.let { condition = condition and it }
        subQueries.addAll(whereSubquery)
    }

    return WhereResult(condition, subQueries)
}
