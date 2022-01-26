package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.parser.ParsedQuery
import org.neo4j.graphql.parser.QueryParser

class TopLevelMatchTranslator(val schemaConfig: SchemaConfig, val variables: Map<String, Any>) {

    fun translateTopLevelMatch(node: Node, varName: String, arguments: Map<String, Any>): OngoingReading {

        val cypherNode = node.asCypherNode(varName)
        val match = Cypher.match(cypherNode)

        val conditions = where(cypherNode, node, varName, arguments)

        return match.where(conditions)
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

        fun handleLogicalOperator(value: Any?, classifier: String, variables: Map<String, Any>): Condition {
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
                            "${classifier}${index + 1}",
                            variables
                        )
                    }
                    else -> values.map { value -> handleLogicalOperator(value, "", variables) }
                }
                else -> emptyList()
            }
        }
        handleLogicalOperators(parsedQuery.and, "And").forEach { result = result.and(it) }
        handleLogicalOperators(parsedQuery.or, "Or").forEach { result = result.or(it) }

        return result
    }

}
