package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.BuildableStatement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.connect.ConnectFieldInput
import org.neo4j.graphql.schema.model.inputs.connect.ConnectFieldInput.ImplementingTypeConnectFieldInput
import org.neo4j.graphql.schema.model.inputs.connect.ConnectInput
import org.neo4j.graphql.translate.where.PrefixUsage
import org.neo4j.graphql.translate.where.createWhere

//TODO complete
class ConnectTranslator(
    private val schemaConfig: SchemaConfig,
    private val queryContext: QueryContext,
    private val parentNode: Node,
    private val varName: ChainString,
    private val parentVar: org.neo4j.cypherdsl.core.Node,
    private val fromCreate: Boolean,
    private val withVars: List<SymbolicName>,
    private val relationField: RelationField,
    private val inputs: List<ImplementingTypeConnectFieldInput>?,
    private val exposeWith: ExposesWith,
    private val refNodes: Collection<Node>,
    private val labelOverride: String?,
    private val includeRelationshipValidation: Boolean = false,
    private val usePrefix: PrefixUsage = PrefixUsage.APPEND
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

            val baseName = varName.appendOnPrevious(index)

            val subQueries = if (relationField.isInterface) {
                refNodes.mapIndexed { i, refNode ->
                    createSubqueryContents(refNode, connect, varName.appendOnPrevious(i))
                }
            } else {
                createSubqueryContents(refNodes.first(), connect, baseName)
                    .wrapList()
            }
            if (subQueries.isNotEmpty()) {
                result = result.with(withVars)
                    .withSubQueries(subQueries)
            }
        }

        return result
    }

    private fun createSubqueryContents(
        relatedNode: Node,
        connect: ImplementingTypeConnectFieldInput,
        baseName: ChainString,
    ): Statement {
        val nodeName = baseName.extend("node")
        val node = (labelOverride
            ?.let { Cypher.node(labelOverride) }
            ?: relatedNode.asCypherNode(queryContext)
                ).named(nodeName.resolveName())

        val (conditions, nestedSubQueries) = getConnectWhere(node, relatedNode, connect)

        var subQuery = Cypher.with(withVars)
            .optionalMatch(node)
            .let { if (conditions != null) it.where(conditions) else it }

        val nestedWithVars = (withVars + node)

        getPreAuth(node, relatedNode)?.let {
            subQuery = subQuery.with(*nestedWithVars.toTypedArray())
                .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }

        val mergeConnectionStatement = getMergeConnectionStatement(node, baseName, connect)
        subQuery = subQuery.call(mergeConnectionStatement)

        if (includeRelationshipValidation) {
            subQuery = addRelationshipValidation(node, relatedNode, subQuery)
        }

        subQuery = addNestedConnects(
            node, relatedNode, nodeName, connect,
            subQuery
                .with(*nestedWithVars.toTypedArray()) // TODO remove with
        )

        getPostAuth(node, relatedNode)?.let {
            subQuery = subQuery.with(*nestedWithVars.toTypedArray())
                .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }

        val ongoingReading = subQuery.withSubQueries(nestedSubQueries)
        if (ongoingReading is BuildableStatement<*>) {
            return ongoingReading.build()
        }
        return ongoingReading.returning(
            Cypher.count(Cypher.asterisk())
                .`as`(
                    ChainString(
                        schemaConfig,
                        "connect",
                        varName,
                        relatedNode
                    ).resolveName()
                )  // TODO why this alias?
        )
            .build()
    }

    private fun getConnectWhere(
        nodeName: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node,
        connect: ImplementingTypeConnectFieldInput
    ): WhereResult {
        val whereNode = connect.where?.node ?: return WhereResult.EMPTY

        // If _on is the only `where`-key and it doesn't contain this implementation, don't connect it
        if (!whereNode.hasFilterForNode(relatedNode)) {
            return WhereResult.EMPTY
        }

        val whereInput = whereNode.withPreferredOn(relatedNode)
        var (conditions, whereSubQueries) = createWhere(
            relatedNode,
            whereInput,
            propertyContainer = nodeName,
            chainStr = ChainString(schemaConfig, nodeName),
            schemaConfig,
            queryContext,
            usePrefix
        )

        if (relatedNode.auth != null) {

            AuthTranslator(
                schemaConfig, queryContext, where = AuthTranslator.AuthOptions(nodeName, relatedNode)
            )
                .createAuth(relatedNode.auth, AuthDirective.AuthOperation.CONNECT)
                ?.let { conditions = conditions and it }
        }
        return WhereResult(conditions, whereSubQueries)
    }

    private fun getPreAuth(
        nodeName: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node
    ): Condition? {
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
                allow = allow
            )
                .createAuth(allow.parentNode.auth, AuthDirective.AuthOperation.CONNECT)
                ?.let { preAuth = preAuth and it }
        }
        return preAuth
    }

    private fun getMergeConnectionStatement(
        nodeName: org.neo4j.cypherdsl.core.Node,
        baseName: ChainString,
        connect: ImplementingTypeConnectFieldInput
    ): Statement {
        val connectedNodes = Cypher.collect(nodeName).`as`("connectedNodes")
        val parentNodes = Cypher.collect(parentVar).`as`("parentNodes")
        val unwoundedParents = Cypher.anyNode(parentVar.requiredSymbolicName)
        val unwoundedConnections = Cypher.anyNode(nodeName.requiredSymbolicName)

        var createDslRelation =
            relationField.createDslRelation(unwoundedParents, unwoundedConnections)
        val edgeSet = if (relationField.properties != null) {
            val prefix = baseName.extend("relationship")
            createDslRelation = createDslRelation.named(prefix.resolveName())
            createSetPropertiesOnCreate(
                createDslRelation,
                connect.edge,
                relationField.properties,
                queryContext
            )
        } else {
            null
        }

        return Cypher
            .with(Cypher.asterisk())
            .with(connectedNodes, parentNodes)
            .call(
                Cypher.with(connectedNodes, parentNodes)
                    .unwind(parentNodes).`as`(unwoundedParents.requiredSymbolicName)
                    .unwind(connectedNodes).`as`(unwoundedConnections.requiredSymbolicName)
                    .merge(createDslRelation)
                    .let { merge -> edgeSet?.let { merge.set(it) } ?: merge }
                    .build()
            )
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
        baseName: ChainString,
        connect: ImplementingTypeConnectFieldInput,
        subQuery: ExposesWith
    ): ExposesWith {
        val connects = connect.connect ?: return subQuery
        return connects.fold(subQuery) { resultQuery, input ->
            addNestedConnect(
                nodeName,
                relatedNode,
                baseName,
                input,
                resultQuery
            )
        }
    }

    private fun addNestedConnect(
        nodeName: org.neo4j.cypherdsl.core.Node,
        relatedNode: Node,
        baseName: ChainString,
        connect: ConnectInput,
        subQuery: ExposesWith
    ): ExposesWith {
        val (inputOnForNode, inputExcludingOnForNode) = when (connect) {
            is ConnectInput.NodeConnectInput -> null to connect
            is ConnectInput.InterfaceConnectInput -> connect.on?.getDataForNode(relatedNode) to connect.getCommonFields(
                relatedNode
            )
        }
        listOf(
            listOf(inputExcludingOnForNode) to baseName,
            inputOnForNode to ChainString(schemaConfig, "on", relatedNode.name),
        )
        var resultQuery: ExposesWith = subQuery
        listOf(
            Triple(listOf(inputExcludingOnForNode), baseName, false),
            Triple(inputOnForNode, ChainString(schemaConfig, "on", relatedNode.name), true),
        ).forEach { (inputs, name, isOn) ->
            // TODO refactor into methods
            inputs?.forEachIndexed { index, input ->
                input.relations.forEach { (relField, v) ->
                    val newRefNodes = relField.getReferenceNodes()
                    newRefNodes.forEach { newRefNode ->
                        val nestedInputs = when (v) {
                            is ConnectFieldInput.UnionConnectFieldInput -> v.getDataForNode(newRefNode)
                                ?: return@forEach

                            is ConnectFieldInput.NodeConnectFieldInputs -> v
                            is ConnectFieldInput.InterfaceConnectFieldInputs -> v
                        }
                        resultQuery = ConnectTranslator(
                            schemaConfig,
                            queryContext,
                            parentNode = relatedNode,
                            varName = name
                                .appendOnPrevious(index.takeIf { isOn })
                                .extend(
                                    relField,
                                    newRefNode.takeIf { relField.isUnion }
                                ),
                            parentVar = nodeName,
                            fromCreate = false, // TODO I think we should pass through the `fromCreate`
                            withVars = withVars + nodeName.requiredSymbolicName,
                            relationField = relField,
                            inputs = nestedInputs,
                            exposeWith = resultQuery,
                            refNodes = listOf(newRefNode),
                            labelOverride = newRefNode.name.takeIf { relField.isUnion },
                            includeRelationshipValidation = !isOn
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
    ): Condition? {
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
                    bind = AuthTranslator.AuthOptions(nodeName, node)
                )
                    .createAuth(node.auth, AuthDirective.AuthOperation.CONNECT)
                    ?.let { postAuth = postAuth and it }
            }
        return postAuth
    }
}
