package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.HasCoalesceValue
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.connection.ConnectionWhere
import org.neo4j.graphql.domain.predicates.ConnectionFieldPredicate
import org.neo4j.graphql.domain.predicates.RelationFieldPredicate
import org.neo4j.graphql.domain.predicates.ScalarFieldPredicate
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.translate.WhereResult

fun createWhere(
    node: FieldContainer<*>?,
    whereInput: WhereInput?,
    propertyContainer: PropertyContainer, // todo rename to dslNode
    chainStr: ChainString? = null,
    schemaConfig: SchemaConfig,
    queryContext: QueryContext,
): WhereResult {
    if (node == null || whereInput == null) return WhereResult.EMPTY
    // TODO harmonize with top level where

    val paramPrefix = chainStr ?: ChainString(schemaConfig, propertyContainer)

    fun resolveRelationCondition(predicate: RelationFieldPredicate): Condition {
        if (propertyContainer !is Node) throw IllegalArgumentException("a nodes is required for relation predicates")
        val field = predicate.field
        val op = predicate.def.operator
        val where = predicate.where
        val param = paramPrefix.extend(field, op.suffix)
        val refNode = field.node ?: throw IllegalArgumentException("Relationship filters must reference nodes")
        val endDslNode = refNode.asCypherNode(queryContext)

        var relCond = field.createDslRelation(propertyContainer, endDslNode).asCondition()

        val endNode = endDslNode.named(param.resolveName())
        createWhere(refNode, where, endNode, chainStr = param, schemaConfig, queryContext)
            .takeIf { it.predicate != null }
            ?.let { innerWhere ->
                check(innerWhere.preComputedSubQueries.isEmpty(), { "TODO implement sub-queries here" })
                val nestedCondition = op.predicateCreator(endNode.requiredSymbolicName)
                    .`in`(
                        CypherDSL
                            .listBasedOn(field.createDslRelation(propertyContainer, endNode))
                            .returning(endNode)
                    )
                    .where(innerWhere.predicate)
                relCond = relCond and nestedCondition
            }

        if (op == org.neo4j.graphql.domain.predicates.RelationOperator.NOT_EQUAL) {
            relCond = relCond.not()
        }
        return relCond
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

            val endDslNode = refNode.asCypherNode(queryContext)


            var relCond = relationField.createDslRelation(propertyContainer, endDslNode).asCondition()

            val thisParam = param.extend(refNode)

            val parameterPrefix = param.extend(
                node,
                field,
                "where",
                field,
                op.suffix // TODO duplicate op
            )

            val cond = CypherDSL.name("Cond")
            val endNode = endDslNode.named(thisParam.resolveName())
            val relation = relationField.createDslRelation(
                propertyContainer,
                endNode,
                thisParam.extend(field.relationshipTypeName)
            )


            val (whereCondition, whereSubquery) = createConnectionWhere(
                whereInput,
                refNode,
                endNode,
                relationField,
                relation,
                parameterPrefix,
                schemaConfig,
                queryContext
            )
            subQueries.addAll(whereSubquery)

            (whereCondition ?: Conditions.isTrue())
                .let { innerWhere ->
                    val nestedCondition = op.predicateCreator(cond)
                        .`in`(
                            CypherDSL
                                .listBasedOn(relation)
                                .returning(innerWhere)
                        )
                        .where(cond.asCondition())
                    relCond = relCond and nestedCondition
                }

            if (op == org.neo4j.graphql.domain.predicates.RelationOperator.NOT_EQUAL) {
                relCond = relCond.not()
            }

            result = result and relCond
        }
        return WhereResult(result, subQueries)
    }


    fun resolveScalarCondition(predicate: ScalarFieldPredicate): Condition {
        val field = predicate.field
        val dbProperty = propertyContainer.property(field.dbPropertyName)
        val property =
            (field as? HasCoalesceValue)
                ?.coalesceValue
                ?.toJavaValue()
                ?.let { Functions.coalesce(dbProperty, it.asCypherLiteral()) }
                ?: dbProperty

        val rhs = if (predicate.value == null) {
            Cypher.literalNull()
        } else {
            //TODO cleanup old naming logic
            queryContext.getNextParam(predicate.value)
//            paramPrefix.extend(predicate.name).resolveParameter(predicate.value)
        }
        return predicate.createCondition(property, rhs)
    }

    var result: Condition? = null
    val subQueries = mutableListOf<Statement>()
    if (whereInput is WhereInput.FieldContainerWhereInput<*>) {
        result = whereInput.reduceNestedConditions { key, index, nested ->
            val (whereCondition, whereSubquery) = createWhere(
                node, nested, propertyContainer,
                paramPrefix.extend(key, index.takeIf { it > 0 }),
                schemaConfig, queryContext
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
            when (predicate) {
                is ScalarFieldPredicate -> resolveScalarCondition(predicate)
                is RelationFieldPredicate -> resolveRelationCondition(predicate)
                is ConnectionFieldPredicate -> {
                    val (whereCondition, whereSubquery) =resolveConnectionCondition(predicate)
                    subQueries.addAll(whereSubquery)
                    whereCondition
                }
                else -> null
            }
                ?.let { result = result and it }
        }
    }

    return WhereResult(result, subQueries)
}
