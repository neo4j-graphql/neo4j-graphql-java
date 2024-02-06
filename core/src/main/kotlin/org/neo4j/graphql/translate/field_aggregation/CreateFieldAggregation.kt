package org.neo4j.graphql.translate.field_aggregation

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.field_arguments.RelationFieldAggregateInputArgs
import org.neo4j.graphql.schema.model.outputs.aggregate.AggregationSelectionFields
import org.neo4j.graphql.schema.model.outputs.aggregate.RelationAggregationSelection
import org.neo4j.graphql.translate.ApocFunctions
import org.neo4j.graphql.translate.AuthTranslator
import org.neo4j.graphql.translate.where.createWhere
import org.neo4j.graphql.utils.ResolveTree

object CreateFieldAggregation {

    fun createFieldAggregation(
        dslNode: org.neo4j.cypherdsl.core.Node,
        relationField: RelationField,
        field: ResolveTree,
        subQueries: MutableList<Statement>,
        schemaConfig: SchemaConfig,
        queryContext: QueryContext,
    ): Any? {
        val referenceNode = relationField.node ?: return null

        val targetRef = referenceNode.asCypherNode(queryContext, referenceNode.name.lowercase())
        val aggregationFields = RelationAggregationSelection(relationField, field)
        val aggregationNodeFields = aggregationFields.node?.parsedSelection
        val aggregationEdgeFields = aggregationFields.edge?.parsedSelection

        val authData = createFieldAggregationAuth(
            referenceNode,
            schemaConfig,
            queryContext,
            targetRef,
            aggregationNodeFields
        )
        val prefix = ChainString(schemaConfig, dslNode, field.name)

        val args = RelationFieldAggregateInputArgs(referenceNode, field.args)
        val whereInput = args.where
        val (predicate, preComputedSubqueries) = createWhere(
            referenceNode,
            whereInput,
            targetRef,
            prefix,
            schemaConfig,
            queryContext
        )

        val where = listOfNotNull(authData, predicate).foldWithAnd()

        val relationship = relationField.createQueryDslRelation(dslNode, targetRef, args.directed)
            .named("edge")
        val matchWherePattern = Cypher
            .with(dslNode)
            .match(relationship)
            .let {
                when {
                    where != null && preComputedSubqueries.isNotEmpty() -> it.withSubQueries(preComputedSubqueries)
                        .with(Cypher.asterisk())
                        .where(where)

                    where != null -> it.where(where)

                    else -> it
                }
            }

        val projections = mutableListOf<Any>()

        aggregationFields.count.takeIf { it.isNotEmpty() }?.let {
            val countRef = queryContext.getNextVariable(prefix.extend("var"))
            projections.addAll(it.project(countRef))
            subQueries += matchWherePattern
                .returning(Cypher.count(targetRef).`as`(countRef))
                .build()
        }

        if (aggregationNodeFields != null) {
            val innerProjection = getAggregationProjectionAndSubqueries(
                prefix,
                matchWherePattern,
                targetRef,
                aggregationNodeFields,
                subQueries,
                queryContext
            )
            projections += aggregationFields.node.aliasOrName
            projections += Cypher.mapOf(*innerProjection.toTypedArray())
        }

        if (aggregationEdgeFields != null) {
            val innerProjection = getAggregationProjectionAndSubqueries(
                prefix,
                matchWherePattern,
                relationship,
                aggregationEdgeFields,
                subQueries,
                queryContext
            )
            projections += aggregationFields.edge.aliasOrName
            projections += Cypher.mapOf(*innerProjection.toTypedArray())
        }

        return Cypher.mapOf(*projections.toTypedArray())
    }

    fun getAggregationProjectionAndSubqueries(
        prefix: ChainString,
        matchPattern: StatementBuilder.OngoingReading,
        targetRef: PropertyContainer,
        fields: AggregationSelectionFields,
        subQueries: MutableList<Statement>,
        queryContext: QueryContext
    ): List<Any> {
        val projection = mutableListOf<Any>()
        fields
            .flatMap { pair -> pair.value.map { pair.key to it } }
            .forEach { (field, data) ->

                //TODO what about auth?

                val property = targetRef.property(field.dbPropertyName)
                val nestedSelection =
                    data.fieldsByTypeName[field.getAggregationSelectionLibraryTypeName()] ?: return@forEach
                val fieldRef = queryContext.getNextVariable(prefix.extend("var"))

                projection += data.aliasOrName
                projection += fieldRef

                val innerProjection = when (field.typeMeta.type.name()) {
                    Constants.STRING -> {
                        val list = Cypher.name("list")
                        subQueries += matchPattern
                            .with(targetRef)
                            .orderBy(Cypher.size(property)).descending()
                            .with(Cypher.collect(property).`as`(list))
                            .returning(stringAggregationProjection(nestedSelection, list).`as`(fieldRef))
                            .build()
                        return@forEach
                    }

                    Constants.ID -> idAggregationProjection(nestedSelection, property)

                    Constants.INT,
                    Constants.BIG_INT,
                    Constants.FLOAT -> defaultAggregationProjection(nestedSelection, property, number = true)

                    Constants.DATE_TIME -> dateTimeAggregationProjection(nestedSelection, property)
                    else -> defaultAggregationProjection(nestedSelection, property, number = false)
                }
                subQueries += matchPattern.returning(innerProjection.`as`(fieldRef)).build()
            }
        return projection
    }


    private fun stringAggregationProjection(nestedSelection: Map<*, ResolveTree>, property: Expression): MapExpression {
        val projection = mutableListOf<Any>()
        nestedSelection.values.map { aggregateField ->
            val reduceFun = when (aggregateField.name) {
                Constants.LONGEST -> Cypher::head
                Constants.SHORTEST -> Cypher::last
                else -> error("unsupported field ${aggregateField.name} for string aggregation")
            }
            projection += aggregateField.aliasOrName
            projection += reduceFun(property)
        }
        return Cypher.mapOf(*projection.toTypedArray())
    }

    private fun idAggregationProjection(nestedSelection: Map<*, ResolveTree>, property: Expression): MapExpression {
        val projection = mutableListOf<Any>()
        nestedSelection.values.map { aggregateField ->
            val reduceFun = when (aggregateField.name) {
                Constants.LONGEST -> Cypher::max
                Constants.SHORTEST -> Cypher::min
                else -> error("unsupported field ${aggregateField.name} for string aggregation")
            }
            projection += aggregateField.aliasOrName
            projection += reduceFun(property)
        }
        return Cypher.mapOf(*projection.toTypedArray())
    }

    private fun defaultAggregationProjection(
        nestedSelection: Map<*, ResolveTree>,
        property: Property,
        number: Boolean
    ): MapExpression {
        val projection = mutableListOf<Any>()
        nestedSelection.values.forEach { aggregateField ->
            val reduceFun = when (aggregateField.name) {
                Constants.MIN -> Cypher::min

                Constants.MAX -> Cypher::max

                Constants.AVERAGE -> Cypher::avg
                    .also { check(number, { "${Constants.AVERAGE} is only supported for numbers" }) }

                Constants.SUM -> Cypher::sum
                    .also { check(number, { "${Constants.SUM} is only supported for numbers" }) }

                else -> error("unsupported field ${aggregateField.name} for aggregation")
            }
            projection += aggregateField.aliasOrName
            projection += reduceFun(property)
        }
        return Cypher.mapOf(*projection.toTypedArray())
    }

    private fun dateTimeAggregationProjection(nestedSelection: Map<*, ResolveTree>, property: Property): MapExpression {
        val projection = mutableListOf<Any>()
        nestedSelection.values.forEach { aggregateField ->
            val reduceFun = when (aggregateField.name) {
                Constants.MIN -> Cypher::min
                Constants.MAX -> Cypher::max
                else -> error("unsupported field ${aggregateField.name} for date aggregation")
            }
            projection += aggregateField.aliasOrName
            projection += ApocFunctions.date.convertFormat(
                Cypher.toString(reduceFun(property)),
                "iso_zoned_date_time".asCypherLiteral(),
                "iso_offset_date_time".asCypherLiteral()
            )
        }
        return Cypher.mapOf(*projection.toTypedArray())
    }


    private fun createFieldAggregationAuth(
        node: Node,
        schemaConfig: SchemaConfig,
        queryContext: QueryContext,
        subqueryNodeAlias: org.neo4j.cypherdsl.core.Node,
        nodeFields: AggregationSelectionFields?
    ): Condition? {
        //TODO harmonize with ProjectionTranslator::createNodeWhereAndParams ?

        val allowAuth =
            AuthTranslator(schemaConfig, queryContext, allow = AuthTranslator.AuthOptions(subqueryNodeAlias, node))
                .createAuth(node.auth, AuthDirective.AuthOperation.READ)
                ?.let { it.apocValidatePredicate(Constants.AUTH_FORBIDDEN_ERROR) }

        val whereAuth =
            AuthTranslator(schemaConfig, queryContext, where = AuthTranslator.AuthOptions(subqueryNodeAlias, node))
                .createAuth(node.auth, AuthDirective.AuthOperation.READ)

        val fieldAuth = nodeFields
            ?.mapNotNull { (field, _) ->
                AuthTranslator(
                    schemaConfig,
                    queryContext,
                    allow = AuthTranslator.AuthOptions(subqueryNodeAlias, node, ChainString(schemaConfig, field))
                )
                    .createAuth(field.auth, AuthDirective.AuthOperation.READ)
            }
            ?.foldWithAnd()
            ?.let { it.apocValidatePredicate(Constants.AUTH_FORBIDDEN_ERROR) }

        return listOfNotNull(allowAuth, whereAuth, fieldAuth).foldWithAnd()
    }
}
