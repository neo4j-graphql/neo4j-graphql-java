package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.PropertyContainer
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.and
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.predicates.ConnectionPredicate
import org.neo4j.graphql.foldWithAnd
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.translate.WhereResult

fun createConnectionWhere(
    whereInput: ConnectionWhere.ImplementingTypeConnectionWhere<*>?,
    implementingType: ImplementingType,
    nodeVariable: PropertyContainer,
    relationship: RelationField,
    relationshipVariable: PropertyContainer,
    schemaConfig: SchemaConfig,
    queryContext: QueryContext,
): WhereResult {

    fun createOnNode(value: WhereInput): WhereResult {
        val result = mutableListOf<Condition?>()
        val subQueries = mutableListOf<Statement>()
        if (value is WhereInput.FieldContainerWhereInput<*>) {
            val (whereCondition, whereSubquery) = createWhere(
                implementingType,
                value,
                nodeVariable,
                schemaConfig,
                queryContext,
            )
            result += whereCondition
            subQueries.addAll(whereSubquery)
        }

        return WhereResult(result.foldWithAnd(), subQueries)
    }

    val subQueries = mutableListOf<Statement>()
    var condition = whereInput?.reduceNestedConditions { nested ->
        val (whereCondition, whereSubquery) = createConnectionWhere(
            nested,
            implementingType,
            nodeVariable,
            relationship,
            relationshipVariable,
            schemaConfig,
            queryContext,
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
                schemaConfig,
                queryContext,
            )

            ConnectionPredicate.Target.NODE -> createOnNode(predicate.value)
        }
        whereCondition?.let { predicate.op.conditionCreator(it) }
            ?.let { condition = condition and it }
        subQueries.addAll(whereSubquery)
    }

    return WhereResult(condition, subQueries)
}
