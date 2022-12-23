package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.FieldContainer
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.filter.FulltextPerIndex
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.parser.ParsedQuery
import org.neo4j.graphql.parser.QueryParser
import org.neo4j.graphql.translate.where.createWhere

class TopLevelMatchTranslator(
    val schemaConfig: SchemaConfig,
    val variables: Map<String, Any>,
    val queryContext: QueryContext
) {

    fun translateTopLevelMatch(
        node: Node,
        cypherNode: org.neo4j.cypherdsl.core.Node,
        fulltextInput: FulltextPerIndex?,
        where: WhereInput?,
        authOperation: AuthDirective.AuthOperation
    ): OngoingReading {

        val varName = ChainString(schemaConfig, cypherNode)

        var (match, conditions) = if (fulltextInput != null) {
            createFulltextSearchMatch(fulltextInput, node, varName)
        } else {
            Cypher.match(cypherNode) to Conditions.noCondition()
        }

        val (whereConditions, subQueries) = createWhere(node, where, cypherNode, varName, schemaConfig, queryContext)
        whereConditions?.let { conditions = conditions.and(it) }

        if (node.auth != null) {
            AuthTranslator(
                schemaConfig,
                queryContext,
                where = AuthTranslator.AuthOptions(cypherNode, node)
            ).createAuth(node.auth, authOperation)
                ?.let { conditions = conditions.and(it) }
        }
        return if (subQueries.isNotEmpty()) {
            (match.withSubQueries(subQueries) as ExposesWith)
                .with(Cypher.asterisk())
                .where(conditions)
        } else {
            match.where(conditions)
        }
    }

    private fun createFulltextSearchMatch(
        fulltextInput: FulltextPerIndex,
        node: Node,
        varName: ChainString
    ): Pair<StatementBuilder.OngoingStandaloneCallWithReturnFields, Condition> {
        if (fulltextInput.size > 1) {
            throw IllegalArgumentException("Can only call one search at any given time");
        }
        val (indexName, indexInput) = fulltextInput.entries.first()

        val thisName = Cypher.name("node").`as`("this")
        val scoreName = Cypher.name("score").`as`("score")
        val call = CypherDSL.call("db.index.fulltext.queryNodes")
            .withArgs(
                indexName.asCypherLiteral(),
                varName.extend("fulltext", indexName, "phrase").resolveParameter(indexInput.phrase)
            )
            .yield(thisName, scoreName)

        var cond = Conditions.noCondition()
        // TODO remove this? https://github.com/neo4j/graphql/issues/1189
        if (node.additionalLabels.isNotEmpty()) {
            // TODO add Functions.labels(#symbolicName) // node.hasLabels()
            node.allLabels(queryContext).forEach {
                cond =
                    cond.and(it.asCypherLiteral().`in`(Cypher.call("labels").withArgs(thisName).asFunction()))
            }
        }

        if (node.fulltextDirective != null) {
            val index = node.fulltextDirective.indexes.find { it.name == indexName }
            (indexInput.score
                ?.let { varName.extend("fulltext", indexName, "score", "EQUAL").resolveParameter(it) }
                ?: index?.defaultThreshold?.let {
                    varName.extend(
                        "fulltext",
                        indexName,
                        "defaultThreshold" // TODO naming: split
                    ).resolveParameter(it)
                })
                ?.let { cond = cond.and(scoreName.eq(it)) }
        }

        return call to cond
    }

    // TODO remove after check if all logic is migrated

    private fun where(
        propertyContainer: PropertyContainer,
        node: Node,
        varName: ChainString,
        whereInput: WhereInput?,
    ): Condition {
        val result = Conditions.noCondition()
        return (whereInput as? Map<*, *>)
            ?.let { QueryParser.parseFilter(it, node.name, node, schemaConfig) }
            ?.let {
                val filterCondition = handleQuery(
                    varName,
//                    normalizeName(Constants.WHERE, varName), // TODO naming
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

    private fun handleQuery(
        variablePrefix: ChainString,
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

                    val cond = Cypher.name(variablePrefix.extend(predicate.field.typeMeta.type, "Cond").resolveName())
                    when (predicate.op) {
                        RelationOperator.SOME -> Predicates.any(cond)
                        RelationOperator.SINGLE -> Predicates.single(cond)
                        RelationOperator.EVERY -> Predicates.all(cond)
                        RelationOperator.NOT -> Predicates.all(cond)
                        RelationOperator.NONE -> Predicates.none(cond)
                        else -> null
                    }?.let {
                        val thisParam = variablePrefix.extend(refNode)
                        val relationshipVariable = thisParam.extend(predicate.field.relationshipTypeName).resolveName()

                        val namedTargetNode = refNode.asCypherNode(queryContext, thisParam)
                        val namedRelation = predicate.createRelation(
                            propertyContainer as org.neo4j.cypherdsl.core.Node,
                            namedTargetNode
                        ).named(relationshipVariable)

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

            val cond = Cypher.name(variablePrefix.extend(predicate.field.typeMeta.type, "Cond").resolveName())
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
                val targetNodeName = variablePrefix.extend(typeName2)
                val targetNode = predicate.relNode.named(targetNodeName.resolveName())
                val parsedQuery2 = QueryParser.parseFilter(value, typeName2, fieldContainer2, schemaConfig)
                val condition = handleQuery(
                    targetNodeName,
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
                variablePrefix.extend(classifier),
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
                        ChainString(schemaConfig, propertyContainer),
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
