package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.Constants.PREDICATE_JOINS
import org.neo4j.graphql.Constants.WHERE_REG_EX
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.fields.*

class CreateWhere(val schemaConfig: SchemaConfig, val queryContext: QueryContext?) {

    //TODO("inline")
    fun createElementWhere(
        whereInput: Any?,
        element: FieldContainer<*>,
        varName: PropertyContainer,
        parameterPrefix: String,
    ): Condition? {
        return createWhere(element, whereInput, varName, parameterPrefix)
    }

    fun createWhere(
        node: FieldContainer<*>,
        whereInputAny: Any?,
        varName: PropertyContainer, // todo rename to dslNode
        chainStr: String? = null,
    ): Condition? {
        // TODO harmonize with top level where
        val whereInput = whereInputAny as? Map<*, *> ?: return null

        var result: Condition? = null

//        fun getParam(key: String) = if (chainStr == null) {
//            schemaConfig.namingStrategy.resolveParameter(varName.requiredSymbolicName.value, key)
//        } else {
//            schemaConfig.namingStrategy.resolveParameter(chainStr, key)
//        }
//
//        listOf(
//            Triple(whereInput.or, Constants.OR, { lhs: Condition?, rhs: Condition -> lhs or rhs }),
//            Triple(whereInput.and, Constants.AND, { lhs: Condition?, rhs: Condition -> lhs and rhs }),
//        ).forEach { (joins, key, reducer) ->
//            if (!joins.isNullOrEmpty()) {
//                var innerCondition: Condition? = null
//                val param = getParam(key)
//                joins.forEachIndexed { index, v ->
//                    createWhere(
//                        node,
//                        v,
//                        varName,
//                        schemaConfig.namingStrategy.resolveParameter(param, index.takeIf { it > 0 })
//                    )
//                        ?.let { innerCondition = reducer(innerCondition, it) }
//                }
//                innerCondition?.let { result = result and it }
//            }
//        }

        whereInput.forEach { (key, value) ->
            val param = if (chainStr == null) {
                schemaConfig.namingStrategy.resolveParameter(varName.requiredSymbolicName.value, key)
            } else {
                schemaConfig.namingStrategy.resolveParameter(chainStr, key)
            }

            if (PREDICATE_JOINS.contains(key)) {
                var innerCondition: Condition? = null
                (value as List<*>).forEachIndexed { index, v ->
                    createWhere(
                        node, value, varName,
                        schemaConfig.namingStrategy.resolveParameter(param, index.takeIf { it > 0 })
                    )?.let {

                        innerCondition = if (Constants.OR == key) {
                            innerCondition or it
                        } else {
                            innerCondition and it
                        }
                    }
                }
                innerCondition?.let { result = result and it }
            }


            val match = WHERE_REG_EX.find(key as String)
            val fieldName = match?.groups?.get("fieldName")?.value ?: return@forEach
            val isAggregate = match.groups["isAggregate"] != null
            val operator = match.groups["operator"]?.value

            val isNot = operator?.startsWith("NOT") ?: false

            val field = node.getField(fieldName) ?: return null

            if (isAggregate) {
                if (field !is RelationField) throw IllegalArgumentException("Aggregate filters must be on relationship fields")

                AggregateWhere(schemaConfig, queryContext)
                    .createAggregateWhere(param, field, varName, value)
                    ?.let { result = result and it }

                return@forEach
            }

            if (varName is Node) {
                if (field is RelationField) {
                    val refNode =
                        field.node ?: throw IllegalArgumentException("Relationship filters must reference nodes")
                    val endDslNode = refNode.asCypherNode(queryContext)


                    var relCond = field.createDslRelation(varName, endDslNode).asCondition()

                    val endNode = endDslNode.named(param)
                    createWhere(refNode, value, endNode, chainStr = param)?.let { innerWhere ->
                        val nestedCondition = RelationOperator
                            .fromValue(operator, endNode.requiredSymbolicName)
                            .`in`(
                                CypherDSL
                                    .listBasedOn(field.createDslRelation(varName, endNode))
                                    .returning(endNode)
                            )
                            .where(innerWhere)
                        relCond = relCond and nestedCondition
                    }

                    if (isNot) {
                        relCond = relCond.not()
                    }

                    result = result and relCond
                    return@forEach
                }

                if (field is ConnectionField) {

                    val nodeEntries = if (field.relationshipField.isUnion) {
                        value as? Map<*, *>
                            ?: throw IllegalArgumentException("Connection filter for union must be a map")
                    } else {
                        // TODO can we use the name somehow else
                        mapOf(field.relationshipField.typeMeta.type.name() to value)
                    }

                    nodeEntries.forEach { (nodeName, whereInput) ->
                        val refNode = field.relationshipField.getNode(nodeName as String)
                            ?: throw IllegalArgumentException("Cannot find referenced node $nodeName")

                        val endDslNode = refNode.asCypherNode(queryContext)


                        var relCond = field.relationshipField.createDslRelation(varName, endDslNode).asCondition()

                        val thisParam = schemaConfig.namingStrategy.resolveName(param, refNode.name)

                        val parameterPrefix = schemaConfig.namingStrategy.resolveName(
                            chainStr,
                            varName.requiredSymbolicName.value,
                            fieldName,
                            "where",
                            key
                        )

                        val cond = CypherDSL.name("Cond")
                        val endNode = endDslNode.named(thisParam)
                        val relation = field.relationshipField.createDslRelation(
                            varName,
                            endNode,
                            schemaConfig.namingStrategy.resolveName(thisParam, field.relationshipTypeName)
                        )

                        (CreateConnectionWhere(schemaConfig, queryContext)
                            .createConnectionWhere(
                                whereInput, refNode,
                                endNode,
                                field.relationshipField,
                                relation,
                                parameterPrefix
                            )
                            ?: Conditions.isTrue())
                            .let { innerWhere ->
                                val nestedCondition = RelationOperator
                                    .fromValue(operator, cond)
                                    .`in`(
                                        CypherDSL
                                            .listBasedOn(relation)
                                            .returning(innerWhere)
                                    )
                                    .where(cond.asCondition())
                                relCond = relCond and nestedCondition
                            }

                        if (isNot) {
                            relCond = relCond.not()
                        }

                        result = result and relCond
                    }
                    return@forEach
                }
            }

            val dbProperty = varName.property(field.dbPropertyName)
            val property =
                (field as? HasCoalesceValue)
                    ?.coalesceValue
                    ?.toJavaValue()
                    ?.let { Functions.coalesce(dbProperty, asCypherLiteral().asCypherLiteral()) }
                    ?: dbProperty

            if (value == null) {
                if (isNot) {
                    result = result and property.isNotNull
                } else {
                    result = result and property.isNull
                }
                return@forEach
            }

            result = result and createWhereClause(CypherDSL.parameter(param, value), property, operator, field)

        }

        return result
    }


    private fun createWhereClause(
        param: Parameter<*>,
        property: Expression,
        operator: String?,
        field: BaseField
    ): Condition {

        val comparisonResolver = comparisonMap[operator]
            ?: throw IllegalStateException("cannot handle operation $operator for ${field.getOwnerName()}.${field.fieldName}")

        if (field is PointField) {
            val paramPoint = Functions.point(param)
            val p = Cypher.name("p")
            val paramPointArray = Cypher.listWith(p).`in`(param).returning(Functions.point(p))

            return when (operator) {
                "LT", "LTE", "GT", "GTE", "DISTANCE" ->
                    comparisonResolver(
                        Functions.distance(property, Functions.point(param.property("point"))),
                        param.property("distance")
                    )
                "NOT_IN", "IN" ->
                    comparisonResolver(property, paramPointArray)
                "NOT_INCLUDES", "INCLUDES" ->
                    comparisonResolver(paramPoint, property)
                else ->
                    property.eq(if (field.typeMeta.type.isList()) paramPointArray else paramPoint)
            }
        }

        if (field.typeMeta.type.name() == Constants.DURATION) {
            return comparisonResolver(Functions.datetime().add(property), Functions.datetime().add(param))
        }

        return when (operator) {
            "NOT_INCLUDES", "INCLUDES" -> comparisonResolver(param, property)
            else -> comparisonResolver(property, param)
        }
    }

    companion object {
        val comparisonMap: Map<String?, (Expression, Expression) -> Condition> = mapOf(
            null to Expression::eq,
            "LT" to Expression::lt,
            "LTE" to Expression::lte,
            "GT" to Expression::gt,
            "GTE" to Expression::gte,
            "DISTANCE" to Expression::eq,
            "NOT_CONTAINS" to { lhs, rhs -> lhs.contains(rhs).not() },
            "CONTAINS" to Expression::contains,
            "NOT_STARTS_WITH" to { lhs, rhs -> lhs.startsWith(rhs).not() },
            "STARTS_WITH" to Expression::startsWith,
            "NOT_ENDS_WITH" to { lhs, rhs -> lhs.endsWith(rhs).not() },
            "ENDS_WITH" to Expression::endsWith,
            "MATCHES" to Expression::matches,
            "NOT_IN" to { lhs, rhs -> lhs.`in`(rhs).not() },
            "IN" to Expression::`in`,
            "NOT_INCLUDES" to { lhs, rhs -> lhs.`in`(rhs).not() },
            "INCLUDES" to Expression::`in`,
        )
    }

}
