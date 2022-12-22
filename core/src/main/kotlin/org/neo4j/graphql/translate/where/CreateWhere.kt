package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.fields.HasCoalesceValue
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere
import org.neo4j.graphql.domain.predicates.ConnectionFieldPredicate
import org.neo4j.graphql.domain.predicates.RelationFieldPredicate
import org.neo4j.graphql.domain.predicates.ScalarFieldPredicate
import org.neo4j.graphql.handler.utils.ChainString

fun createWhere(
    node: FieldContainer<*>?,
    whereInput: WhereInput?,
    propertyContainer: PropertyContainer, // todo rename to dslNode
    chainStr: ChainString? = null,
    schemaConfig: SchemaConfig,
    queryContext: QueryContext?,
): Condition? {
    if (node == null || whereInput == null) return null
    // TODO harmonize with top level where

    val paramPrefix =chainStr ?: ChainString(schemaConfig, propertyContainer)

    fun resolveRelationCondition(predicate: RelationFieldPredicate): Condition {
        if (propertyContainer !is Node) throw IllegalArgumentException("a nodes is required for relation predicates")
        val field = predicate.field
        val op = predicate.def.operator
        val where = predicate.where
        val param = paramPrefix.extend( field, op.suffix)
        val refNode = field.node ?: throw IllegalArgumentException("Relationship filters must reference nodes")
        val endDslNode = refNode.asCypherNode(queryContext)

        var relCond = field.createDslRelation(propertyContainer, endDslNode).asCondition()

        val endNode = endDslNode.named(param.resolveName())
        createWhere(refNode, where, endNode, chainStr = param, schemaConfig, queryContext)
            ?.let { innerWhere ->
                val nestedCondition = op.predicateCreator(endNode.requiredSymbolicName)
                    .`in`(
                        CypherDSL
                            .listBasedOn(field.createDslRelation(propertyContainer, endNode))
                            .returning(endNode)
                    )
                    .where(innerWhere)
                relCond = relCond and nestedCondition
            }

        if (op == org.neo4j.graphql.domain.predicates.RelationOperator.NOT_EQUAL) {
            relCond = relCond.not()
        }
        return relCond
    }

    fun resolveConnectionCondition(predicate: ConnectionFieldPredicate): Condition? {
        val field = predicate.field
        val op = predicate.def.operator
        val where = predicate.where

        if (node !is Node) throw IllegalArgumentException("a nodes is required for relation predicates")
        val relationField = field.relationshipField

        val nodeEntries = when (where) {
            is ConnectionWhere.UnionConnectionWhere -> where.dataPerNode.mapKeys { node.name() }
            is ConnectionWhere.ImplementingTypeConnectionWhere ->
                // TODO can we use the name somehow else
                mapOf(relationField.typeMeta.type.name() to where)
            else -> throw IllegalStateException("Unsupported where type")
        }
        var result: Condition? = null

        val param = paramPrefix.extend(field, op.suffix)
        nodeEntries.forEach { (nodeName, whereInput) ->
            val refNode = relationField.getNode(nodeName)
                ?: throw IllegalArgumentException("Cannot find referenced node $nodeName")

            val endDslNode = refNode.asCypherNode(queryContext)


            var relCond = relationField.createDslRelation(node, endDslNode).asCondition()

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
                node,
                endNode,
                thisParam.extend(field.relationshipTypeName)
            )

            (
                    createConnectionWhere(
                        whereInput,
                        refNode,
                        endNode,
                        relationField,
                        relation,
                        parameterPrefix,
                        schemaConfig,
                        queryContext
                    )
                        ?: Conditions.isTrue())
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
        return result
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
            Cypher.parameter(
                schemaConfig.namingStrategy.resolveParameter(paramPrefix, predicate.resolver.name),
                predicate.value
            )
        }
        return predicate.resolver.createCondition(property, rhs)
    }

    var result: Condition? = null

    if (whereInput is WhereInput.FieldContainerWhereInput<*>) {
        result = whereInput.reduceNestedConditions { key, index, nested ->
            createWhere(
                node, nested, propertyContainer,
                paramPrefix.extend(key, index.takeIf { it > 0 }),
                schemaConfig, queryContext
            )
        }

        whereInput.aggregate?.forEach { (field, input) ->
            TODO()
//                AggregateWhere(schemaConfig, queryContext)
//                    .createAggregateWhere(param, field, varName, value)
//                    ?.let { result = result and it }
        }

        whereInput.predicates.forEach { predicate ->
            when (predicate) {
                is ScalarFieldPredicate -> resolveScalarCondition(predicate)
                is RelationFieldPredicate -> resolveRelationCondition(predicate)
                is ConnectionFieldPredicate -> resolveConnectionCondition(predicate)
                else -> null
            }
                ?.let { result = result and it }
        }
    }

    return result
}
