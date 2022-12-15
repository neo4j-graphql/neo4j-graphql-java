package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.PropertyContainer
import org.neo4j.graphql.QueryContext
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.and
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.dto.ConnectionWhere
import org.neo4j.graphql.domain.dto.WhereInput
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.predicates.ConnectionPredicate

//TODO completed
fun createConnectionWhere(
    whereInput: ConnectionWhere.ImplementingTypeConnectionWhere,
    node: Node,
    nodeVariable: PropertyContainer,
    relationship: RelationField,
    relationshipVariable: PropertyContainer,
    parameterPrefix: String,
    schemaConfig: SchemaConfig,
    queryContext: QueryContext?
): Condition? {

    fun createOnNode(key: String, value: WhereInput): Condition? {
        var result: Condition? = null

        if (value is WhereInput.FieldContainerWhereInput) {
                    if (!value.hasFilterForNode(node)) {
                        throw IllegalArgumentException("_on is used as the only argument and node is not present within")
                    }

            val inputOnForNode = value.on?.get(node)
            val inputExcludingOnForNode = value.excluding(inputOnForNode)

            createWhere(
                node,
                inputExcludingOnForNode,
                nodeVariable,
                schemaConfig.namingStrategy.resolveName(parameterPrefix, key),
                schemaConfig,
                queryContext
            )
                ?.let { result = result and it }

            if (inputOnForNode != null) {
                createWhere(
                    node,
                    value.withPreferredOn(node),
                    nodeVariable,
                    schemaConfig.namingStrategy.resolveName(parameterPrefix, key, "on", node.name),
                    schemaConfig,
                    queryContext
                )
                    ?.let { result = result and it }
            }
        }
        return result
    }

    var result = whereInput.reduceNestedConditions { key, index, nested ->
        createConnectionWhere(
            nested,
            node,
            nodeVariable,
            relationship,
            relationshipVariable,
            schemaConfig.namingStrategy.resolveName(parameterPrefix, key, index),
            schemaConfig,
            queryContext
        )
    }

    whereInput.predicates?.forEach { predicate ->
        when (predicate.target) {
            ConnectionPredicate.Target.EDGE -> createWhere(
                relationship.properties,
                predicate.value,
                relationshipVariable,
                schemaConfig.namingStrategy.resolveName(parameterPrefix, predicate.key),
                schemaConfig,
                queryContext
            )

            ConnectionPredicate.Target.NODE -> createOnNode(predicate.key, predicate.value)
        }
            ?.let { predicate.op.conditionCreator(it) }
            ?.let { result = result and it }
    }

    return result
}
