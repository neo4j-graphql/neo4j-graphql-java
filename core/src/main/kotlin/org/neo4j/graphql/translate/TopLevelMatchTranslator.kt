package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.parser.ParsedQuery
import org.neo4j.graphql.parser.QueryParser

class TopLevelMatchTranslator(val schemaConfig: SchemaConfig, val variables: Map<String, Any>) {

    fun translateTopLevelMatch(node: Node, varName: String, arguments: Map<String, Any>): OngoingReading {

        val cypherNode = node.asCypherNode(varName)
        val match = Cypher.match(cypherNode)
        // TODO fulltext

        val conditions = where(cypherNode, node, varName, arguments)

        return match.where(conditions)
    }

    fun createWhere(node: Node, varName: String, arguments: Map<String, Any>) {

    }

    fun where(
        propertyContainer: PropertyContainer,
        node: Node,
        varName: String,
        arguments: Map<String, Any>,
    ): Condition {
        val result = Conditions.noCondition()
        val whereInput = arguments[Constants.WHERE]
        return (whereInput as? Map<*, *>)
            ?.let { QueryParser.parseFilter(it, node.name, node, SchemaConfig()) }
            ?.let {
                val filterCondition = handleQuery(
                    normalizeName(Constants.WHERE, varName),
                    "",
                    propertyContainer,
                    it,
                    node.name,
                    node
                )
                result.and(filterCondition)
            }
            ?: result
    }

    fun handleQuery(
        variablePrefix: String,
        variableSuffix: String,
        propertyContainer: PropertyContainer,
        parsedQuery: ParsedQuery,
        typeName: String,
        fieldContainer: FieldContainer<*>

    ): Condition {
        var result = parsedQuery.getFieldConditions(propertyContainer, variablePrefix, variableSuffix, schemaConfig)

        for (predicate in parsedQuery.relationPredicates) {
            val value = predicate.value

            if (value == null) {
                val existsCondition = predicate.createExistsCondition(propertyContainer)
                result = result.and(existsCondition)
                continue
            }
            if (value !is Map<*, *>) {
                throw IllegalArgumentException("Only object values are supported for filtering on queried relation ${predicate.value}, but got ${value.javaClass.name}")
            }

            if (predicate.connectionField != null) {
                val nodeEntries = if (predicate.field.isUnion) {
                    value
                } else {
                    mapOf(predicate.targetName to value)
                }
                nodeEntries.forEach { (nodeName, value) ->
                    val refNode = predicate.field.getNode(nodeName as String)
                        ?: throw IllegalStateException("Cannot resolve node for connection ${predicate.field.getOwnerName()}.${predicate.field.fieldName}")

                    val cond = Cypher.name(normalizeName(variablePrefix, predicate.field.typeMeta.type.name(), "Cond"))
                    when (predicate.op) {
                        RelationOperator.SOME -> Predicates.any(cond)
                        RelationOperator.SINGLE -> Predicates.single(cond)
                        RelationOperator.EVERY -> Predicates.all(cond)
                        RelationOperator.NOT -> Predicates.all(cond)
                        RelationOperator.NONE -> Predicates.none(cond)
                        else -> null
                    }?.let {
                        val thisParam = normalizeName(variablePrefix, refNode.name)
                        val relationshipVariable = normalizeName(thisParam, predicate.field.relationshipTypeName)

                        val namedTargetNode = refNode.asCypherNode().named(thisParam)
                        val namedRelation = predicate.createRelation(propertyContainer as org.neo4j.cypherdsl.core.Node, namedTargetNode).named(relationshipVariable)

//                        if (predicate.op == RelationOperator.NOT || predicate.op == RelationOperator.NONE){
//                            // TODO https://github.com/neo4j/graphql/issues/925
//                            result = result.and(CypherDSL.match(predicate.createRelation(propertyContainer, refNode.asCypherNode())).asCondition())
//                        }

                        val relCondition =
                            createConnectionWhere(
                                refNode,
                                namedTargetNode,
                                predicate.field.properties,
                                namedRelation,
                                value as Map<*, *>,
                                variableSuffix,
                                schemaConfig
                            )
                        var where = it.`in`(
                            Cypher.listBasedOn(namedRelation).returning(relCondition)
                        )
                            .where(cond.asCondition())
                        if (predicate.op == RelationOperator.NOT) {
                            where = where.not()
                        }
                        result = result.and(where)
                    }
                }
                continue
            }

            val cond = Cypher.name(normalizeName(variablePrefix, predicate.field.typeMeta.type.name(), "Cond"))
            when (predicate.op) {
                RelationOperator.SOME -> Predicates.any(cond)
                RelationOperator.SINGLE -> Predicates.single(cond)
                RelationOperator.EVERY -> Predicates.all(cond)
                RelationOperator.NOT -> Predicates.all(cond)
                RelationOperator.NONE -> Predicates.none(cond)
                else -> null
            }?.let {
                val typeName2 = predicate.targetName ?: TODO("union")
                val fieldContainer2 = predicate.targetFieldContainer ?: TODO("union")
                val targetNode = predicate.relNode.named(normalizeName(variablePrefix, typeName2))
                val parsedQuery2 = QueryParser.parseFilter(value, typeName2, fieldContainer2, schemaConfig)
                val condition = handleQuery(
                    targetNode.requiredSymbolicName.value,
                    "",
                    targetNode,
                    parsedQuery2,
                    typeName2,
                    fieldContainer2
                )
                var where = it
                    .`in`(
                        Cypher.listBasedOn(
                            predicate.createRelation(propertyContainer as org.neo4j.cypherdsl.core.Node, targetNode)
                        ).returning(condition)
                    )
                    .where(cond.asCondition())
                if (predicate.op == RelationOperator.NOT) {
                    where = where.not()
                }
                result = result.and(where)
            }
        }

        fun handleLogicalOperator(value: Any?, classifier: String): Condition {
            val objectValue = value as? Map<*, *>
                ?: throw IllegalArgumentException("Only object values are supported for logical operations, but got ${value?.javaClass?.name}")

            val parsedNestedQuery = QueryParser.parseFilter(objectValue, typeName, fieldContainer, schemaConfig)
            return handleQuery(
                variablePrefix + classifier,
                variableSuffix,
                propertyContainer,
                parsedNestedQuery,
                typeName,
                fieldContainer
            )
        }

        fun handleLogicalOperators(values: List<*>?, classifier: String): List<Condition> {
            return when {
                values?.isNotEmpty() == true -> when {
                    values.size > 1 -> values.mapIndexed { index, value ->
                        handleLogicalOperator(
                            value,
                            "${classifier}${index + 1}"
                        )
                    }
                    else -> values.map { value -> handleLogicalOperator(value, "") }
                }
                else -> emptyList()
            }
        }
        handleLogicalOperators(parsedQuery.and, "And").forEach { result = result.and(it) }
        handleLogicalOperators(parsedQuery.or, "Or").forEach { result = result.or(it) }

        return result
    }

    private fun createConnectionWhere(
        node: Node,
        cypherTargetNode: org.neo4j.cypherdsl.core.Node,
        edgeProperties: RelationshipProperties?,
        relation: Relationship,
        value: Map<*, *>,
        suffix: String,
        schemaConfig: SchemaConfig
    ): Condition {
        val queriedFields = value.toMutableMap()

        var result = Conditions.noCondition()

        fun handle(
            prefix: String,
            fieldContainer: FieldContainer<*>,
            targetName: String,
            propertyContainer: PropertyContainer
        ) {
            (queriedFields.remove(prefix)?.let { it to false }
                ?: queriedFields.remove("${prefix}_NOT")?.let { it to true })
                ?.let { (value, isNot) ->
                    val pq = QueryParser.parseFilter(value as Map<*, *>, targetName, fieldContainer, schemaConfig)
                    handleQuery(
                        propertyContainer.requiredSymbolicName.value,
                        suffix,
                        propertyContainer,
                        pq,
                        targetName,
                        fieldContainer
                    )
                        .let { if (isNot) it.not() else it }
                        .let { result = result.and(it) }
                }
        }

        handle(Constants.NODE_FIELD, node, node.name, cypherTargetNode)
        edgeProperties?.let { handle(Constants.EDGE_FIELD, it, it.interfaceName, relation) }

        fun handleLogicalOperators(op: String, classifier: String): List<Condition> {
            val list = queriedFields.remove(op)
                ?.let { it as? List<*> ?: throw IllegalStateException("expected $op to be of type list") }
            return when {
                list?.isNotEmpty() == true -> when {
                    list.size > 1 -> list.mapIndexed { index, value ->
                        createConnectionWhere(
                            node,
                            cypherTargetNode,
                            edgeProperties,
                            relation,
                            value as Map<*, *>,
                            "$suffix${classifier}${index + 1}",
                            schemaConfig
                        )
                    }
                    else -> list.map { value ->
                        createConnectionWhere(
                            node,
                            cypherTargetNode,
                            edgeProperties,
                            relation,
                            value as Map<*, *>,
                            suffix,
                            schemaConfig
                        )
                    }
                }
                else -> emptyList()
            }
        }
        handleLogicalOperators(Constants.AND, "And").forEach { result = result.and(it) }
        handleLogicalOperators(Constants.OR, "Or").forEach { result = result.or(it) }

        if (queriedFields.isNotEmpty()) {
            throw IllegalArgumentException("queried unknown fields ${queriedFields.keys} on connection ${node.name}.${edgeProperties?.interfaceName}")
        }
        return result
    }
}
