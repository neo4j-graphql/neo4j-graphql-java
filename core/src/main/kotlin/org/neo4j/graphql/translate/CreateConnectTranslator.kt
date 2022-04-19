package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.translate.where.CreateWhere
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

//TODO complete
class CreateConnectTranslator(
    private val schemaConfig: SchemaConfig,
    private val queryContext: QueryContext?,
    private val parentNode: Node,
    private val varName: String,
    private val parentVar: org.neo4j.cypherdsl.core.Node,
    private val fromCreate: Boolean,
    private val withVars: List<SymbolicName>,
    private val relationField: RelationField,
    private val value: Any?,
    private val exposeWith: ExposesWith,
    private val refNodes: List<Node>,
    private val labelOverride: String?,
    private val includeRelationshipValidation: Boolean,
) {

    companion object {

        fun createConnectAndParams(
            schemaConfig: SchemaConfig,
            queryContext: QueryContext?,
            parentNode: Node,
            varName: String,
            parentVar: org.neo4j.cypherdsl.core.Node,
            fromCreate: Boolean,
            withVars: List<SymbolicName>,
            relationField: RelationField,
            value: Any?,
            exposeWith: ExposesWith,
            refNodes: List<Node>,
            labelOverride: String?,
            includeRelationshipValidation: Boolean = false,
        ): ExposesWith {
            return CreateConnectTranslator(
                schemaConfig,
                queryContext,
                parentNode,
                varName,
                parentVar,
                fromCreate,
                withVars,
                relationField,
                value,
                exposeWith,
                refNodes,
                labelOverride,
                includeRelationshipValidation
            )
                .createConnectAndParams()
        }
    }

    private fun createConnectAndParams(): ExposesWith {
        val values = if (relationField.typeMeta.type.isList()) {
            value as? List<*> ?: throw IllegalArgumentException("expected a list")
        } else {
            listOf(value)
        }
        var result = exposeWith

        values.forEachIndexed { index, connect ->
            if (parentNode.auth != null && !fromCreate) {
                AuthTranslator(
                    schemaConfig,
                    queryContext,
                    where = AuthTranslator.AuthOptions(parentVar, parentNode)
                )
                    .createAuth(parentNode.auth, AuthDirective.AuthOperation.CONNECT)
                    ?.let { result = result.with(withVars).where(it) }
            }

            val baseName = varName + index

            val subquery = if (relationField.isInterface) {
                refNodes.mapNotNull { refNode ->
                    createSubqueryContents(refNode, connect, baseName)
                }
                    .takeIf { it.isNotEmpty() }
                    ?.let { Cypher.union(it) }
            } else {
                createSubqueryContents(refNodes.first(), connect, baseName)
            }
            if (subquery != null) {
                result = result.with(withVars).call(subquery)
            }
        }

        return result
    }

    private fun createSubqueryContents(
        relatedNode: Node,
        connectAny: Any?,
        baseName: String,
    ): Statement? {
        val connect = connectAny as? Map<*, *> ?: return null

        val nodeName = (
                labelOverride
                    ?.let { Cypher.node(labelOverride) }
                    ?: relatedNode.asCypherNode(queryContext)
                ).named(schemaConfig.namingStrategy.resolveName(baseName, "node"))

        val conditions = getConnectWhere(nodeName, relatedNode, connect)

        var subQuery = CypherDSL.with(withVars)
            .optionalMatch(nodeName)
            .let { if (conditions != null) it.where(conditions) else it }

        getPreAuth(nodeName, relatedNode)?.let {
            subQuery = subQuery.with(*(withVars + nodeName).toTypedArray()).call(it)
        }

        val mergeConnectionStatement = getMergeConnectionStatement(nodeName, baseName, connect)
        subQuery = subQuery.call(mergeConnectionStatement)

        if (includeRelationshipValidation) {
            subQuery = addRelationshipValidation(nodeName, relatedNode, subQuery)
        }

        subQuery = addNestedConnects(nodeName, relatedNode, connect, subQuery)

        getPostAuth(nodeName, relatedNode)?.let {
            subQuery = subQuery.with(*(withVars + nodeName).toTypedArray()).call(it)
        }

        return subQuery
            .returning(Functions.count(Cypher.asterisk()))
            .build()
    }

    private fun getConnectWhere(
        nodeName: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node,
        connect: Map<*, *>
    ): Condition? {
        val where = connect[Constants.WHERE] as? Map<*, *> ?: return null
        val whereNode = where[Constants.NODE_FIELD] as? Map<*, *> ?: return null

        // If _on is the only where key and it doesn't contain this implementation, don't connect it
        val on = whereNode[Constants.ON] as? Map<*, *>
        if (on != null && whereNode.size == 1 && !on.containsKey(relatedNode.name)) {
            return null
        }

        val whereInput = (on?.get(relatedNode.name) as? Map<*, *>)?.let { onFields ->
            // If this where key is also inside _on for this implementation, use the one in _on instead
            whereNode.toMutableMap().also { it.putAll(onFields) }
        } ?: whereNode


        var conditions = CreateWhere(schemaConfig, queryContext)
            .createWhere(relatedNode, whereInput, varName = nodeName)

        if (relatedNode.auth != null) {

            AuthTranslator(
                schemaConfig, queryContext, where = AuthTranslator.AuthOptions(nodeName, relatedNode)
            )
                .createAuth(relatedNode.auth, AuthDirective.AuthOperation.CONNECT)
                ?.let { conditions = conditions and it }
        }
        return conditions
    }

    private fun getPreAuth(
        nodeName: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node
    ): Statement? {
        val allows = mutableListOf(AuthTranslator.AuthOptions(nodeName, relatedNode))
        if (!fromCreate) {
            allows += AuthTranslator.AuthOptions(parentVar, parentNode)
        }
        var preAuth: Condition? = null
        allows.forEachIndexed { index, allow ->
            if (allow.parentNode.auth == null) return@forEachIndexed

            AuthTranslator(
                schemaConfig,
                queryContext,
                allow = allow.copy(
                    chainStr = schemaConfig.namingStrategy.resolveName(
                        allow.varName.requiredSymbolicName.value,
                        allow.parentNode.name + index,
                        "allow"
                    )
                )
            )
                .createAuth(allow.parentNode.auth, AuthDirective.AuthOperation.CONNECT)
                ?.let { preAuth = preAuth and it }
        }
        return preAuth.apocValidate(Constants.AUTH_FORBIDDEN_ERROR)
    }

    private fun getMergeConnectionStatement(
        nodeName: org.neo4j.cypherdsl.core.Node,
        baseName: String,
        connect: Map<*, *>
    ): ResultStatement {
        var createDslRelation = relationField.createDslRelation(parentVar, nodeName)
        val edgeSet = if (relationField.properties != null) {
            createDslRelation =
                createDslRelation.named(schemaConfig.namingStrategy.resolveName(baseName, "relationship"))
            CreateSetPropertiesTranslator
                .createSetProperties(
                    createDslRelation,
                    connect[Constants.EDGE_FIELD],
                    CreateSetPropertiesTranslator.Operation.CREATE,
                    relationField.properties,
                    schemaConfig
                )
        } else {
            emptySet()
        }

        //https://neo4j.com/developer/kb/conditional-cypher-execution/
        return Cypher.with(parentVar, nodeName)
            .with(parentVar, nodeName)
            .where(parentVar.isNotNull.and(nodeName.isNotNull))
            .merge(createDslRelation)
            .let { if (edgeSet.isNotEmpty()) it.set(edgeSet) else it }
            .returning(Functions.count(Cypher.asterisk()))
            .build()
    }

    private fun addRelationshipValidation(
        nodeName: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node,
        subQuery: StatementBuilder.OngoingReading
    ): StatementBuilder.OngoingReading {
        var resultQuery = subQuery
        listOf(parentNode to parentVar, relatedNode to nodeName)
            .forEach { (node, varName) ->
                CreateRelationshipValidationTranslator
                    .createRelationshipValidations(node, varName, queryContext, schemaConfig)
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        resultQuery = resultQuery
                            .with(*(withVars + nodeName).toTypedArray())
                            .withSubQueries(it)
                    }
            }
        return resultQuery
    }

    private fun addNestedConnects(
        nodeName: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node,
        connect: Map<*, *>,
        subQuery: ExposesWith
    ): ExposesWith {
        val connects = connect[Constants.CONNECT_FIELD]?.let { nestedConnectAny ->
            if (nestedConnectAny is List<*>) {
                nestedConnectAny
            } else {
                listOf(nestedConnectAny)
            }
        } ?: return subQuery

        var resultQuery = subQuery

        connects
            .filterIsInstance<Map<*, *>>()
            .forEach { c -> resultQuery = addNestedConnect(nodeName, relatedNode, c, resultQuery) }

        return resultQuery
    }

    private fun addNestedConnect(
        nodeName: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node,
        connect: Map<*, *>,
        subQuery: ExposesWith
    ): ExposesWith {
        val on = connect[Constants.ON] as? Map<*, *>
        var resultQuery: ExposesWith = subQuery
        connect.forEach { (k, v) ->

            if (k == Constants.ON) {
                return@forEach
            }

            if (relationField.isInterface && on?.get(relatedNode.name) != null) {
                val onList = on[relatedNode.name] as? List<*> ?: listOf(on[relatedNode.name])
                if (onList.find { (it as? Map<*, *>)?.containsKey(k) == true } != null) {
                    return@forEach
                }
            }

            val relField = relatedNode.getField(k as String) as? RelationField ?: return@forEach
            val newRefNodes = mutableListOf<Node>()
            if (relField.isUnion) {
                newRefNodes += (v as Map<*, *>).mapNotNull { relField.getNode(it.key as String) }
            } else {
                // TODO what about interfaces?
                relField.node?.let { newRefNodes += it }
            }

            newRefNodes.forEach { newRefNode ->
                resultQuery = createConnectAndParams(
                    schemaConfig,
                    queryContext,
                    parentNode = relatedNode,
                    varName = schemaConfig.namingStrategy.resolveName(
                        nodeName.requiredSymbolicName.value,
                        k,
                        newRefNode.name.takeIf { relField.isUnion }),
                    parentVar = nodeName,
                    fromCreate = false, // TODO I think we should pass through the `fromCreate`
                    withVars = withVars + nodeName.requiredSymbolicName,
                    relationField = relField,
                    value = if (relField.isUnion) (v as? Map<*, *>)?.get(newRefNode.name) else v,
                    exposeWith = resultQuery,
                    refNodes = listOf(newRefNode),
                    labelOverride = newRefNode.name.takeIf { relField.isUnion },
                    includeRelationshipValidation = true
                )
            }
        }

        if (relationField.isInterface && on?.get(relatedNode.name) != null) {
            val onConnects = on[relatedNode.name] as? List<*> ?: listOf(on[relatedNode.name])

            // TODO merge with the block above
            onConnects.forEachIndexed { onConnectIndex, onConnectAny ->
                val onConnect = onConnectAny as Map<*, *>
                onConnect.forEach { k, v ->
                    val relField = relatedNode.getField(k as String) as? RelationField ?: return@forEach
                    val newRefNodes = mutableListOf<Node>()
                    if (relField.isUnion) {
                        newRefNodes += (v as Map<*, *>).mapNotNull { relField.getNode(it.key as String) }
                    } else {
                        // TODO what about interfaces?
                        relField.node?.let { newRefNodes += it }
                    }

                    newRefNodes.forEach { newRefNode ->
                        resultQuery = createConnectAndParams(
                            schemaConfig,
                            queryContext,
                            parentNode = relatedNode,
                            varName = schemaConfig.namingStrategy.resolveName(
                                nodeName.requiredSymbolicName.value,
                                "on",
                                relatedNode.name + onConnectIndex,
                                k
                            ),
                            parentVar = nodeName,
                            fromCreate = false, // TODO do we need to pass through the `fromCreate`?
                            withVars = withVars + nodeName.requiredSymbolicName,
                            relationField = relField,
                            value = if (relField.isUnion) (v as? Map<*, *>)?.get(newRefNode.name) else v,
                            exposeWith = resultQuery,
                            refNodes = listOf(newRefNode),
                            labelOverride = newRefNode.name.takeIf { relField.isUnion },
                        )
                    }
                }
            }
        }

        return resultQuery
    }

    private fun getPostAuth(
        nodeName: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node
    ): Statement? {
        var postAuth: Condition? = null
        listOf(parentNode.takeIf { !fromCreate }, relatedNode)
            .filterNotNull()
            .forEachIndexed { i, node ->
                if (node.auth == null) {
                    return@forEachIndexed
                }

                AuthTranslator(
                    schemaConfig,
                    queryContext,
                    skipRoles = true,
                    skipIsAuthenticated = true,
                    bind = AuthTranslator.AuthOptions(
                        nodeName,
                        node,
                        chainStr = schemaConfig.namingStrategy.resolveName(
                            nodeName.requiredSymbolicName.value + node.name + i,
                            "bind"
                        )
                    )
                )
                    .createAuth(node.auth, AuthDirective.AuthOperation.CONNECT)
                    ?.let { postAuth = postAuth and it }
            }
        return postAuth.apocValidate(Constants.AUTH_FORBIDDEN_ERROR)
    }
}
