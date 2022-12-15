package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.ExposesCreate
import org.neo4j.cypherdsl.core.ExposesMerge
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.dto.*
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.AugmentationHandlerV2
import org.neo4j.graphql.translate.*
import org.neo4j.graphql.utils.ResolveTree

class UpdateResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): AugmentedField? {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.UPDATE)) {
                return null
            }

            val responseType = addResponseType(node, node.typeNames.updateResponse, Constants.Types.UpdateInfo)
            val coordinates = addMutationField(node.rootTypeFieldNames.update, responseType.asRequiredType()) { args ->
                generateWhereIT(node)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }

                generateContainerUpdateIT(node)
                    ?.let { args += inputValue(Constants.UPDATE_FIELD, it.asType()) }

                generateContainerConnectInputIT(node)
                    ?.let { args += inputValue(Constants.CONNECT_FIELD, it.asType()) }

                generateContainerDisconnectInputIT(node)
                    ?.let { args += inputValue(Constants.DISCONNECT_FIELD, it.asType()) }

                generateContainerRelationCreateInputIT(node)
                    ?.let { args += inputValue(Constants.CREATE_FIELD, it.asType()) }

                generateContainerDeleteInputIT(node)
                    ?.let { args += inputValue(Constants.DELETE_FIELD, it.asType()) }

                generateContainerConnectOrCreateInputIT(node)
                    ?.let { args += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.asType()) }
            }

            return AugmentedField(coordinates, UpdateResolver(ctx.schemaConfig, node))
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()
        val dslNode = node.asCypherNode(queryContext, variable)
        val resolveTree = ResolveTree.resolve(env) // todo move into parent class

        val updateInput = resolveTree.args[Constants.UPDATE_FIELD]?.let { RelationFieldsInput(node, it) }
        val connectInput = resolveTree.args[Constants.CONNECT_FIELD]?.let { RelationFieldsInput(node, it) }
        val disconnectInput = resolveTree.args[Constants.DISCONNECT_FIELD]?.let { RelationFieldsInput(node, it) }
        val createInput = resolveTree.args[Constants.CREATE_FIELD]?.let { RelationFieldsInput(node, it) }
        val deleteInput = resolveTree.args[Constants.DELETE_FIELD]?.let { RelationFieldsInput(node, it) }
        val connectOrCreateInput =
            resolveTree.args[Constants.CONNECT_OR_CREATE_FIELD]?.let { RelationFieldsInput(node, it) }
        val assumeReconnecting = connectInput != null && disconnectInput != null

        val withVars = listOf(dslNode.requiredSymbolicName)


        var ongoingReading: ExposesWith = TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
            .translateTopLevelMatch(node, dslNode, env.arguments, AuthDirective.AuthOperation.UPDATE)

        if (updateInput != null) {
            ongoingReading = CreateUpdate(
                dslNode,
                updateInput,
                dslNode,
                chainStr = null,
                node,
                withVars,
                queryContext,
                ChainString(schemaConfig, resolveTree.name),
                true,
                schemaConfig,
                ongoingReading
            )
                .createUpdateAndParams()
        }

        disconnectInput?.getInputsPerField()?.forEach { (relField, refNodes, input) ->

            if (relField.isInterface) {
                ongoingReading = CreateDisconnectTranslator(
                    withVars,
                    input,
                    ChainString(schemaConfig, dslNode, "disconnect", relField),
                    relField,
                    dslNode,
                    queryContext,
                    schemaConfig,
                    refNodes,
                    null,
                    node,
                    ChainString(schemaConfig, resolveTree.name, "disconnect", relField),
                    ongoingReading
                )
                    .createDisconnectAndParams()
            } else {
                refNodes.forEach { refNode ->
                    ongoingReading = CreateDisconnectTranslator(
                        withVars,
                        if (relField.isUnion) (UnionInput(input)).getDataForNode(refNode) else input,
                        ChainString(
                            schemaConfig,
                            dslNode,
                            "disconnect",
                            relField,
                            refNode.name.takeIf { relField.isUnion }),
                        relField,
                        dslNode,
                        queryContext,
                        schemaConfig,
                        listOf(refNode),
                        refNode.name.takeIf { relField.isUnion },
                        node,
                        ChainString(schemaConfig,
                            resolveTree.name,
                            "disconnect",
                            relField,
                            refNode.name.takeIf { relField.isUnion }),
                        ongoingReading
                    )
                        .createDisconnectAndParams()
                }
            }
        }

        connectInput?.getInputsPerField()?.forEach { (relField, refNodes, input) ->
            if (relField.isInterface) {
                ongoingReading = CreateConnectTranslator.createConnectAndParams(
                    schemaConfig,
                    queryContext,
                    node,
                    ChainString(schemaConfig, dslNode, "connect", relField),
                    dslNode,
                    false,
                    withVars,
                    relField,
                    input,
                    ongoingReading,
                    refNodes,
                    null,
                    includeRelationshipValidation = assumeReconnecting
                )
            } else {
                refNodes.forEach { refNode ->
                    ongoingReading = CreateConnectTranslator.createConnectAndParams(
                        schemaConfig,
                        queryContext,
                        node,
                        ChainString(schemaConfig,
                            dslNode,
                            "connect",
                            relField,
                            refNode.name.takeIf { relField.isUnion }),
                        dslNode,
                        false,
                        withVars,
                        relField,
                        if (relField.isUnion) (UnionInput(input)).getDataForNode(refNode) else input,
                        ongoingReading,
                        listOf(refNode),
                        refNode.name.takeIf { relField.isUnion }
                        //TODO why not includeRelationshipValidation
                    )
                }
            }
        }

        createInput?.getInputsPerField()?.forEach { (relField, refNodes, input) ->

            refNodes.forEach { refNode ->
                val creates: List<CreateInput> = if (relField.isInterface) {
                    val listInput = input as? List<*> ?: listOf(input)

                    listInput
                        .map { CreateInput(it) }
                        .mapNotNull {
                            val nodeForImpl = InterfaceInput(it.node)
                                .getNodeInputForNode(refNode)
                                ?: return@mapNotNull null
                            CreateInput(nodeForImpl, it.edge)
                        }

                } else if (relField.isUnion) {
                    listOf(CreateInput(UnionInput(input).getDataForNode(refNode)))
                } else {
                    listOf(CreateInput(input))
                }
                if (creates.isEmpty()) {
                    return@forEach
                }

                creates.forEachIndexed { index, create ->
                    val targetNode = node.asCypherNode(
                        queryContext, ChainString(
                            schemaConfig,
                            dslNode,
                            "create",
                            relField,
                            refNode.takeIf { relField.isInterface || relField.isUnion },
                            index,
                            "node"
                        ).resolveName()
                    )
                    ongoingReading = CreateTranslator(schemaConfig, queryContext)
                        .createCreateAndParams(
                            refNode,
                            targetNode,
                            create.node,
                            listOf(dslNode.requiredSymbolicName)
                        ) {
                            (ongoingReading as ExposesCreate).create(it)
                        }

                    val relationship = relField.createDslRelation(
                        dslNode, targetNode, ChainString(
                            schemaConfig,
                            dslNode,
                            "create",
                            relField,
                            refNode.takeIf { relField.isInterface || relField.isUnion },
                            index,
                            "relationship"
                        )
                    )
                    ongoingReading = (ongoingReading as ExposesMerge).merge(relationship)
                        .let { merge ->
                            relField.properties?.let { properties ->
                                CreateSetPropertiesTranslator
                                    .createSetProperties(
                                        relationship,
                                        create.edge,
                                        CreateSetPropertiesTranslator.Operation.CREATE,
                                        properties,
                                        schemaConfig
                                    )
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { merge.set(it).with(dslNode) }
                            } ?: merge
                        }
                }
            }
        }

        if (deleteInput != null) {
            ongoingReading = DeleteTranslator.createDeleteAndParams(
                node,
                deleteInput,
                ChainString(schemaConfig, dslNode, "delete"),
                dslNode,
                withVars,
                ChainString(schemaConfig, resolveTree.name, "args", "delete"),
                schemaConfig,
                queryContext,
                ongoingReading
            )
        }

        connectOrCreateInput?.getInputsPerField()?.forEach { (relField, refNodes, input) ->
            refNodes.forEach { refNode ->

                val inputs = if (relField.isUnion) {
                    UnionInput(input).getDataForNode(refNode)
                } else {
                    input
                }
                    ?.let { it as? List<*> ?: listOf(it) }
                    ?.filterNotNull()
                    ?.map { ConnectOrCreateInput(it) }

                ongoingReading = ConnectOrCreateTranslator.createConnectOrCreateAndParams(
                    inputs,
                    ChainString(
                        schemaConfig,
                        dslNode,
                        "connectOrCreate",
                        relField,
                        refNode.takeIf { relField.isUnion }
                    ),
                    dslNode,
                    relField,
                    refNode,
                    withVars,
                    ongoingReading,
                    schemaConfig,
                    queryContext
                )
            }

        }

        val projection = resolveTree.getFieldOfType(node.typeNames.updateResponse, node.plural)
            ?.let {
                CreateProjection()
                    .createProjectionAndParams(node, dslNode, it, null, schemaConfig, env.variables, queryContext)
            }

        projection?.authValidate?.let {
            ongoingReading = ongoingReading
                .with(dslNode)
                .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }

        return ongoingReading
            .maybeWith(withVars)
            .withSubQueries(projection?.subQueries)
            .returning(
                *listOfNotNull(
                    projection?.let { Functions.collectDistinct(dslNode.project(it.projection)).`as`(Constants.DATA) },
//                    TODO subscription
                ).toTypedArray()
            )
            .build()
    }

}

