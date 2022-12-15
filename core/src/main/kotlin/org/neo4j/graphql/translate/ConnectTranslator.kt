package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.dto.ConnectFieldInput
import org.neo4j.graphql.domain.dto.ConnectInput
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.translate.where.createWhere

//TODO complete
class ConnectTranslator(
    private val schemaConfig: SchemaConfig,
    private val queryContext: QueryContext?,
    private val parentNode: Node,
    private val varName: String,
    private val parentVar: org.neo4j.cypherdsl.core.Node,
    private val fromCreate: Boolean,
    private val withVars: List<SymbolicName>,
    private val relationField: RelationField,
    private val inputs: ConnectFieldInput.NodeConnectFieldInputs?,
    private val exposeWith: ExposesWith,
    private val refNodes: Collection<Node>,
    private val labelOverride: String?,
    private val includeRelationshipValidation: Boolean = false,
) {

    fun createConnectAndParams(): ExposesWith {
        var result = exposeWith
        inputs?.forEachIndexed { index, connect ->
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
                refNodes.map { refNode ->
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
        connect: ConnectFieldInput.NodeConnectFieldInput,
        baseName: String,
    ): Statement {
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
        connect: ConnectFieldInput.NodeConnectFieldInput
    ): Condition? {
        val where = connect.where ?: return null
        val whereNode = where.node

        // If _on is the only `where`-key and it doesn't contain this implementation, don't connect it
        if (!whereNode.hasFilterForNode(relatedNode)) {
            return null
        }

        val whereInput = whereNode.withPreferredOn(relatedNode)
        var conditions = createWhere(
            relatedNode,
            whereInput,
            propertyContainer = nodeName,
            chainStr = null,
            schemaConfig,
            queryContext
        )

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
        connect: ConnectFieldInput.NodeConnectFieldInput
    ): ResultStatement {
        var createDslRelation = relationField.createDslRelation(parentVar, nodeName)
        val edgeSet = if (relationField.properties != null) {
            createDslRelation =
                createDslRelation.named(schemaConfig.namingStrategy.resolveName(baseName, "relationship"))
            createSetProperties(
                createDslRelation,
                connect.edge,
                Operation.CREATE,
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
                RelationshipValidationTranslator
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
        connect: ConnectFieldInput.NodeConnectFieldInput,
        subQuery: ExposesWith
    ): ExposesWith {
        val connects = connect.connect ?: return subQuery
        return connects.fold(subQuery) { resultQuery, input ->
            addNestedConnect(
                nodeName,
                relatedNode,
                input,
                resultQuery
            )
        }
    }

    private fun addNestedConnect(
        nodeName: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node,
        connect: ConnectInput,
        subQuery: ExposesWith
    ): ExposesWith {
        val on = connect.on
        val onInterface = on?.get(relatedNode)?.takeIf { relationField.isInterface }
        var resultQuery: ExposesWith = subQuery
        connect.forEach { (relField, v) ->
            if (onInterface?.find { it.containsKey(relField) } != null) {
                return@forEach
            }

            val newRefNodes = mutableListOf<Node>()
            if (v is ConnectFieldInput.UnionConnectFieldInput) {
                newRefNodes += v.keys
            } else {
                // TODO what about interfaces?
                relField.node?.let { newRefNodes += it }
            }

            newRefNodes.forEach { newRefNode ->
                val nestedInputs = when (v) {
                    is ConnectFieldInput.UnionConnectFieldInput -> v[newRefNode] ?: return@forEach
                    is ConnectFieldInput.NodeConnectFieldInputs -> v
                    else -> throw IllegalStateException("unknown type for ConnectFieldInput")
                }
                resultQuery = ConnectTranslator(
                    schemaConfig,
                    queryContext,
                    parentNode = relatedNode,
                    varName = schemaConfig.namingStrategy.resolveName(
                        nodeName.requiredSymbolicName.value,
                        relField,
                        newRefNode.name.takeIf { relField.isUnion }),
                    parentVar = nodeName,
                    fromCreate = false, // TODO I think we should pass through the `fromCreate`
                    withVars = withVars + nodeName.requiredSymbolicName,
                    relationField = relField,
                    inputs = nestedInputs,
                    exposeWith = resultQuery,
                    refNodes = listOf(newRefNode),
                    labelOverride = newRefNode.name.takeIf { relField.isUnion },
                    includeRelationshipValidation = true
                ).createConnectAndParams()
            }
        }

        if (onInterface != null) {

            // TODO merge with the block above
            onInterface.forEachIndexed { onConnectIndex, onConnect ->
                onConnect.forEach { relField, v ->
                    val newRefNodes = mutableListOf<Node>()
                    if (relField.isUnion) {
                        newRefNodes += (v as Map<*, *>).mapNotNull { relField.getNode(it.key as String) }
                    } else {
                        // TODO what about interfaces?
                        relField.node?.let { newRefNodes += it }
                    }

                    newRefNodes.forEach { newRefNode ->
                        val nestedInputs = when (v) {
                            is ConnectFieldInput.UnionConnectFieldInput -> v[newRefNode] ?: return@forEach
                            is ConnectFieldInput.NodeConnectFieldInputs -> v
                            else -> throw IllegalStateException("unknown type for ConnectFieldInput")
                        }
                        resultQuery = ConnectTranslator(
                            schemaConfig,
                            queryContext,
                            parentNode = relatedNode,
                            varName = schemaConfig.namingStrategy.resolveName(
                                nodeName.requiredSymbolicName.value,
                                "on",
                                relatedNode.name + onConnectIndex,
                                relField
                            ),
                            parentVar = nodeName,
                            fromCreate = false, // TODO do we need to pass through the `fromCreate`?
                            withVars = withVars + nodeName.requiredSymbolicName,
                            relationField = relField,
                            inputs = nestedInputs,
                            exposeWith = resultQuery,
                            refNodes = listOf(newRefNode),
                            labelOverride = newRefNode.name.takeIf { relField.isUnion },
                        ).createConnectAndParams()
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
