package org.neo4j.graphql.translate.where

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.fields.HasCoalesceValue
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.predicates.ConnectionFieldPredicate
import org.neo4j.graphql.domain.predicates.RelationFieldPredicate
import org.neo4j.graphql.domain.predicates.ScalarFieldPredicate
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
    propertyContainer: PropertyAccessor,
    schemaConfig: SchemaConfig,
    queryContext: QueryContext,
    addNotNullCheck: Boolean = false
): WhereResult {
    if (whereInput == null) return WhereResult.EMPTY
    // TODO harmonize with top level where

    fun resolveRelationCondition(predicate: RelationFieldPredicate): WhereResult {
        if (predicate.where != null && predicate.where.isEmpty()) {
            return WhereResult.EMPTY
        }
        require(propertyContainer is Node) { "a node is required for relation predicates" }
        val field = predicate.field
        val op = predicate.def.operator

        val nodes = field.extractOnTarget(
            { node -> listOf(node) },
            { interfaze -> interfaze.implementations.values },
            { union ->
                val dataPerNode = (predicate.where as WhereInput.UnionWhereInput).dataPerNode
                if (dataPerNode.isEmpty()) union.nodes.values else dataPerNode.keys
            }
        )

        var allConditions: Condition? = null
        nodes.forEach { refNode ->

            val endNode =
                refNode.asCypherNode(queryContext).named(queryContext.getNextVariable(propertyContainer.name()))

            val where = when (val where = predicate.where) {
                is WhereInput.UnionWhereInput -> where.getDataForNode(refNode)
                else -> where
            }

            val (nestedCondition, preComputedSubQueries) = createWhere(
                refNode,
                where,
                endNode,
                schemaConfig,
                queryContext,
            )
            if (preComputedSubQueries.isNotEmpty()) {
                TODO()
            }

            if (field is RelationField) {
                val relation = field.createDslRelation(propertyContainer, endNode)
                val cond = op.createRelationCondition(relation, nestedCondition)
                allConditions = allConditions and cond.let {
                    if (predicate.where == null) it.not() else it
                }
            } else {
                TODO()
            }
        }

        return WhereResult(allConditions)


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
                mapOf(relationField.type.name() to where)

            else -> throw IllegalStateException("Unsupported where type")
        }
        var result: Condition? = null

        val subQueries = mutableListOf<Statement>()
        nodeEntries.forEach { (nodeName, whereInput) ->

            val implementingType: ImplementingType
            val endNode: Node
            if (whereInput is ConnectionWhere.InterfaceConnectionWhere) {
                implementingType = whereInput.interfaze
                endNode = Cypher.anyNode(queryContext.getNextVariable(propertyContainer.name()))
            } else {
                implementingType = relationField.getNode(nodeName)
                    ?: throw IllegalArgumentException("Cannot find referenced node $nodeName")
                endNode =
                    implementingType.asCypherNode(queryContext)
                        .named(queryContext.getNextVariable(propertyContainer.name()))
            }

            val relation = relationField.createDslRelation(propertyContainer, endNode).named("edge")

            var (nestedCondition, preComputedSubQueries) = createConnectionWhere(
                whereInput,
                implementingType,
                endNode,
                relationField,
                relation,
                schemaConfig,
                queryContext,
            )
            if (preComputedSubQueries.isNotEmpty()) {
                TODO()
            }

            if (implementingType is Interface) {
                var lableConditions: Condition? = null
                for (implementation in implementingType.implementations.values) {
                    lableConditions = lableConditions or Cypher.hasLabelsOrType(
                        endNode.requiredSymbolicName,
                        queryContext.resolve(implementation.name)
                    )
                }
                if (lableConditions != null) {
                    nestedCondition = nestedCondition and lableConditions
                }
            }

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
            queryContext.getNextParam(predicate.value)
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
        result = whereInput.reduceNestedConditions { nested ->
            val (whereCondition, whereSubquery) = createWhere(
                node, nested, propertyContainer,
                schemaConfig,
                queryContext
            )
            subQueries.addAll(whereSubquery)
            whereCondition
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
    if (whereInput is WhereInput.UnionWhereInput) {
        val union = whereInput.getDataForNode(node as org.neo4j.graphql.domain.Node)
        val (whereCondition, whereSubquery) = createWhere(
            node, union, propertyContainer, schemaConfig, queryContext
        )
        result = whereCondition
        subQueries.addAll(whereSubquery)
    }

    if (whereInput is WhereInput.InterfaceWhereInput && !whereInput.typeNameIn.isNullOrEmpty()) {
        var labelCondition: Condition? = null
        for (typeName in whereInput.typeNameIn) {
            labelCondition =
                labelCondition or Cypher.hasLabelsOrType(
                    (propertyContainer as Node).requiredSymbolicName,
                    queryContext.resolve(typeName)
                )
        }
        if (labelCondition != null) {
            result = result and labelCondition
        }
    }

    return WhereResult(result, subQueries)
}

