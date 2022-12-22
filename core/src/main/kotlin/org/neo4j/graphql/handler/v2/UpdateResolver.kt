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
import org.neo4j.graphql.domain.inputs.ScalarProperties
import org.neo4j.graphql.domain.inputs.connect.ConnectFieldInput
import org.neo4j.graphql.domain.inputs.connect_or_create.ConnectOrCreateFieldInput
import org.neo4j.graphql.domain.inputs.create.CreateInput
import org.neo4j.graphql.domain.inputs.create.RelationFieldInput
import org.neo4j.graphql.domain.inputs.disconnect.DisconnectFieldInput
import org.neo4j.graphql.domain.inputs.update.UpdateResolverInputs
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

        val arguments = UpdateResolverInputs(node, resolveTree.args)
        val assumeReconnecting = arguments.connect != null && arguments.disconnect != null

        val withVars = listOf(dslNode.requiredSymbolicName)


        var ongoingReading: ExposesWith = TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
            .translateTopLevelMatch(node, dslNode, null, arguments.where, AuthDirective.AuthOperation.UPDATE)

        if (arguments.update != null) {
            ongoingReading = UpdateTranslator(
                dslNode,
                arguments.update,
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

        arguments.disconnect?.relations?.forEach { (relField, input) ->
            val data = when (input) {
                is DisconnectFieldInput.NodeDisconnectFieldInputs -> listOf(
                    Triple(listOf(relField.node!!), input, null)
                )

                is DisconnectFieldInput.InterfaceDisconnectFieldInputs -> listOf(
                    Triple(relField.getReferenceNodes(), input, null)
                )

                is DisconnectFieldInput.UnionDisconnectFieldInput -> input.dataPerNode.map { (node, inputs) ->
                    Triple(listOf(node), inputs, node.name)
                }
            }
            data.forEach { (refNodes, inp, nameClassifier) ->
                DisconnectTranslator(
                    withVars,
                    inp,
                    ChainString(schemaConfig, dslNode, "disconnect", relField, nameClassifier),
                    relField,
                    dslNode,
                    queryContext,
                    schemaConfig,
                    refNodes,
                    null,
                    node,
                    ChainString(schemaConfig, resolveTree.name, "disconnect", relField, nameClassifier),
                    ongoingReading
                )
                    .createDisconnectAndParams()
            }
        }

        arguments.connect?.relations?.forEach { (relField, input) ->
            val data = when (input) {
                is ConnectFieldInput.NodeConnectFieldInputs -> listOf(
                    Triple(listOf(relField.node!!), input, null)
                )

                is ConnectFieldInput.InterfaceConnectFieldInputs -> listOf(
                    Triple(relField.getReferenceNodes(), input, null)
                )

                is ConnectFieldInput.UnionConnectFieldInput -> input.dataPerNode.map { (node, inputs) ->
                    Triple(listOf(node), inputs, node.name)
                }
            }
            data.forEach { (refNodes, inp, nameClassifier) ->
                ongoingReading = ConnectTranslator(
                    schemaConfig,
                    queryContext,
                    node,
                    ChainString(schemaConfig, dslNode, "connect", relField, nameClassifier),
                    dslNode,
                    false,
                    withVars,
                    relField,
                    inp,
                    ongoingReading,
                    refNodes,
                    labelOverride = nameClassifier,
                    includeRelationshipValidation = assumeReconnecting
                ).createConnectAndParams()
            }
        }

        arguments.create?.relations?.forEach { (relField, input) ->
            relField.getReferenceNodes().forEach { refNode ->
                val creates: List<Pair<CreateInput, ScalarProperties?>>? = when (input) {
                    is RelationFieldInput.NodeCreateCreateFieldInputs -> input.map { it.node to it.edge }
                    is RelationFieldInput.InterfaceCreateFieldInputs -> input.mapNotNull {
                        val n = it.node.getDataForNode(refNode) ?: return@mapNotNull null
                        n to it.edge
                    }

                    is RelationFieldInput.UnionFieldInput -> input.getDataForNode(node)?.map { it.node to it.edge }
                }

                creates?.forEachIndexed { index, (createNode, createEdge) ->
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
                            createNode,
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
                                createSetProperties(
                                    relationship,
                                    createEdge,
                                    Operation.CREATE,
                                    properties,
                                    schemaConfig
                                )?.let { merge.set(it).with(dslNode) }
                            } ?: merge
                        }
                }
            }
        }

        arguments.delete?.let { deleteInput ->
            ongoingReading = createDeleteAndParams(
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

        arguments.connectOrCreate?.relations?.forEach { (relField, input) ->
            relField.getReferenceNodes().forEach { refNode ->

                val inputs = when (input) {
                    is ConnectOrCreateFieldInput.NodeConnectOrCreateFieldInputs -> input
                    is ConnectOrCreateFieldInput.UnionConnectOrCreateFieldInput -> input.getDataForNode(refNode)
                        ?: emptyList()
                }

                ongoingReading = createConnectOrCreate(
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
                    queryContext,
                    schemaConfig
                )
            }

        }

        val projection = resolveTree.getFieldOfType(node.typeNames.updateResponse, node.plural)
            ?.let {
                ProjectionTranslator()
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

