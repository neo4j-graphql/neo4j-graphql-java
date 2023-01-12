package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.schema.model.inputs.connect.ConnectFieldInput
import org.neo4j.graphql.schema.model.inputs.connect.ConnectFieldInput.ImplementingTypeConnectFieldInput
import org.neo4j.graphql.schema.model.inputs.connect.ConnectInput
import org.neo4j.graphql.handler.utils.ChainString
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

            val baseName = varName.extend(index)

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

        getPreAuth(node, relatedNode)?.let {
            subQuery = subQuery.with(*(withVars + node).toTypedArray())
                .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }

        val mergeConnectionStatement = getMergeConnectionStatement(node, baseName, connect)
        subQuery = subQuery.call(mergeConnectionStatement)

        if (includeRelationshipValidation) {
            subQuery = addRelationshipValidation(node, relatedNode, subQuery)
        }

        subQuery = addNestedConnects(node, relatedNode, nodeName, connect, subQuery)

        getPostAuth(node, relatedNode)?.let {
            subQuery = subQuery.with(*(withVars + node).toTypedArray())
                .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }

        return subQuery
            .withSubQueries(nestedSubQueries)
            .returning(Functions.count(Cypher.asterisk()))
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
    ): ResultStatement {
        var createDslRelation = relationField.createDslRelation(parentVar, nodeName)
        val edgeSet = if (relationField.properties != null) {
            createDslRelation = createDslRelation.named(baseName.extend("relationship").resolveName())
            createSetProperties(
                createDslRelation,
                connect.edge,
                Operation.CREATE,
                relationField.properties,
                schemaConfig
            )
        } else {
            null
        }

        //https://neo4j.com/developer/kb/conditional-cypher-execution/
        return Cypher.with(Cypher.asterisk())
            .with(parentVar, nodeName)
            .where(parentVar.isNotNull.and(nodeName.isNotNull))
            .merge(createDslRelation)
            .let { merge -> edgeSet?.let { merge.set(it) } ?: merge }
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
                            varName = name.extend(
                                index.takeIf { isOn },
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
                    bind = AuthTranslator.AuthOptions(
                        nodeName,
                        node,
                        chainStr = ChainString(schemaConfig, nodeName, node, i, "bind")
                    )
                )
                    .createAuth(node.auth, AuthDirective.AuthOperation.CONNECT)
                    ?.let { postAuth = postAuth and it }
            }
        return postAuth
    }
}
