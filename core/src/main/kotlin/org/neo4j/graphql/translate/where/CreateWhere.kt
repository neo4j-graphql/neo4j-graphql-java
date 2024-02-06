package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.HasCoalesceValue
import org.neo4j.graphql.domain.predicates.ConnectionFieldPredicate
import org.neo4j.graphql.domain.predicates.RelationFieldPredicate
import org.neo4j.graphql.domain.predicates.ScalarFieldPredicate
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connection.ConnectionWhere
import org.neo4j.graphql.translate.WhereResult

/**
 *
 * @param propertyContainer [PropertyContainer] or [Expression]
 */
fun createWhere(
    node: FieldContainer<*>?,
    whereInput: WhereInput?,
    propertyContainer: HasProperties,
    chainStr: ChainString? = null,
    schemaConfig: SchemaConfig,
    queryContext: QueryContext,
    usePrefix: PrefixUsage = PrefixUsage.NONE, // TODO remove
    addNotNullCheck: Boolean = false
): WhereResult {
    if (whereInput == null) return WhereResult.EMPTY
    // TODO harmonize with top level where

    val paramPrefix = chainStr ?: ChainString(schemaConfig, propertyContainer)

    fun resolveRelationCondition(predicate: RelationFieldPredicate): WhereResult {
        require(propertyContainer is Node) { "a nodes is required for relation predicates" }
        val field = predicate.field
        val op = predicate.def.operator
        val refNode = field.node ?: throw IllegalArgumentException("Relationship filters must reference nodes")
        val endNode = refNode.asCypherNode(queryContext).named(queryContext.getNextVariable(propertyContainer.name()))

        val (nestedCondition, preComputedSubQueries) = createWhere(
            refNode,
            predicate.where,
            endNode,
            chainStr = paramPrefix.extend(field, op.suffix),
            schemaConfig,
            queryContext,
            usePrefix
        )

        val relation = field.createDslRelation(propertyContainer, endNode)
        val cond = op.createRelationCondition(relation, nestedCondition)

        return WhereResult(cond)
    }

    fun resolveConnectionCondition(predicate: ConnectionFieldPredicate): WhereResult {
        val field = predicate.field
        val op = predicate.def.operator
        val where = predicate.where

        if (propertyContainer !is Node) throw IllegalArgumentException("a nodes is required for relation predicates")
        val relationField = field.relationshipField

        val nodeEntries = when (where) {
            is ConnectionWhere.UnionConnectionWhere -> where.dataPerNode.mapKeys { propertyContainer.name() }
            is ConnectionWhere.ImplementingTypeConnectionWhere<*> ->
                // TODO can we use the name somehow else
                mapOf(relationField.typeMeta.type.name() to where)

            else -> throw IllegalStateException("Unsupported where type")
        }
        var result: Condition? = null

        val param = paramPrefix.extend(field, op.suffix)
        val subQueries = mutableListOf<Statement>()
        nodeEntries.forEach { (nodeName, whereInput) ->
            val refNode = relationField.getNode(nodeName)
                ?: throw IllegalArgumentException("Cannot find referenced node $nodeName")

            val endNode =
                refNode.asCypherNode(queryContext).named(queryContext.getNextVariable(propertyContainer.name()))

            val parameterPrefix = param.extend(
                node,
                field,
                "where",
                field,
                op.suffix // TODO duplicate op
            )

            val relation = relationField.createDslRelation(propertyContainer, endNode).named("edge")

            val (nestedCondition, preComputedSubQueries) = createConnectionWhere(
                whereInput,
                refNode,
                endNode,
                relationField,
                relation,
                parameterPrefix,
                schemaConfig,
                queryContext,
                usePrefix
            )

            val cond = op.createRelationCondition(relation, nestedCondition)
            result = result and cond
        }
        return WhereResult(result, subQueries)
    }


    fun resolveScalarCondition(predicate: ScalarFieldPredicate): Condition {
        val field = predicate.field
        val dbProperty = when (propertyContainer) {
            is PropertyContainer -> propertyContainer.property(field.dbPropertyName)
            is Expression -> propertyContainer.property(field.dbPropertyName)
            else -> throw IllegalStateException("Unsupported property container type ${propertyContainer::class}")
        }
        val property =
            (field as? HasCoalesceValue)
                ?.coalesceValue
                ?.toJavaValue()
                ?.let { Cypher.coalesce(dbProperty, it.asCypherLiteral()) }
                ?: dbProperty

        val rhs = if (predicate.value == null) {
            Cypher.literalNull()
        } else {
            var v = predicate.value
            if (v is String && v.startsWith(Constants.JWT_PREFIX)) {
                val key = v.substring(Constants.JWT_PREFIX.length)
                v = queryContext.auth?.jwt
                val param = Cypher.parameter(Constants.JWT, v)
                return param.property(key).isNotNull and predicate.createCondition(property, param.property(key))
            } else {
                queryContext.getNextParam(v)
            }
        }
        val condition = predicate.createCondition(property, rhs)
        return if (addNotNullCheck) {
            dbProperty.isNotNull and condition
        } else {
            condition
        }
    }

    var result: Condition? = null
    val subQueries = mutableListOf<Statement>()
    if (whereInput is WhereInput.FieldContainerWhereInput<*>) {
        result = whereInput.reduceNestedConditions { _, index, nested ->
            val (whereCondition, whereSubquery) = createWhere(
                node, nested, propertyContainer,
                paramPrefix,
                schemaConfig, queryContext, usePrefix
            )
            subQueries.addAll(whereSubquery)
            whereCondition
        }

        whereInput.relationAggregate.forEach { (field, input) ->
            if (propertyContainer !is Node) throw IllegalArgumentException("a nodes is required for relation predicates")
            AggregateWhere(schemaConfig, queryContext)
                .createAggregateWhere(paramPrefix, field, propertyContainer, input)
                .let { aggregationResult ->
                    aggregationResult.predicate?.let { result = result and it }
                    subQueries.addAll(aggregationResult.preComputedSubQueries)
                }
        }

        whereInput.predicates.forEach { predicate ->
            val (whereCondition, whereSubquery) = when (predicate) {
                is ScalarFieldPredicate -> WhereResult(resolveScalarCondition(predicate))
                is RelationFieldPredicate -> resolveRelationCondition(predicate)
                is ConnectionFieldPredicate -> resolveConnectionCondition(predicate)
                else -> WhereResult.EMPTY
            }
            subQueries.addAll(whereSubquery)
            if (whereCondition != null) {
                result = result and whereCondition
            }
        }
    }

    return WhereResult(result, subQueries)
}

