package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.ScalarProperties
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.connect.ConnectFieldInput
import org.neo4j.graphql.schema.model.inputs.connect.ConnectInput
import org.neo4j.graphql.schema.model.inputs.connect_or_create.ConnectOrCreateFieldInput
import org.neo4j.graphql.schema.model.inputs.connect_or_create.ConnectOrCreateInput
import org.neo4j.graphql.schema.model.inputs.create.CreateInput
import org.neo4j.graphql.schema.model.inputs.create.RelationFieldInput
import org.neo4j.graphql.schema.model.inputs.create.RelationInput
import org.neo4j.graphql.schema.model.inputs.delete.DeleteInput
import org.neo4j.graphql.schema.model.inputs.disconnect.DisconnectFieldInput
import org.neo4j.graphql.schema.model.inputs.disconnect.DisconnectInput
import org.neo4j.graphql.schema.model.inputs.update.UpdateInput
import org.neo4j.graphql.translate.*
import org.neo4j.graphql.translate.Operation
import org.neo4j.graphql.translate.where.PrefixUsage
import org.neo4j.graphql.utils.ResolveTree

class UpdateResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.NodeAugmentation {

        override fun augmentNode(node: Node): List<AugmentedField> {
            if (node.annotations.mutation?.update == false) {
                return emptyList()
            }

            val responseType = addResponseType(
                node,
                node.operations.mutationResponseTypeNames.update,
                Constants.Types.UpdateInfo
            )
            val coordinates =
                addMutationField(node.operations.rootTypeFieldNames.update, responseType.asRequiredType()) { args ->

                WhereInput.NodeWhereInput.Augmentation
                    .generateWhereIT(node, ctx)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }

                UpdateInput.NodeUpdateInput.Augmentation
                    .generateContainerUpdateIT(node, ctx)
                    ?.let { args += inputValue(Constants.UPDATE_FIELD, it.asType()) }

                ConnectInput.NodeConnectInput.Augmentation
                    .generateContainerConnectInputIT(node, ctx)
                    ?.let { args += inputValue(Constants.CONNECT_FIELD, it.asType()) }

                DisconnectInput.NodeDisconnectInput.Augmentation
                    .generateContainerDisconnectInputIT(node, ctx)
                    ?.let { args += inputValue(Constants.DISCONNECT_FIELD, it.asType()) }

                RelationInput.Augmentation
                    .generateContainerRelationCreateInputIT(node, ctx)
                    ?.let { args += inputValue(Constants.CREATE_FIELD, it.asType()) }

                DeleteInput.NodeDeleteInput.Augmentation
                    .generateContainerDeleteInputIT(node, ctx)
                    ?.let { args += inputValue(Constants.DELETE_FIELD, it.asType()) }

                ConnectOrCreateInput.Augmentation
                    .generateContainerConnectOrCreateInputIT(node, ctx)
                    ?.let { args += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.asType()) }
            }

            return AugmentedField(coordinates, UpdateResolver(ctx.schemaConfig, node)).wrapList()
        }
    }

    private class InputArguments(node: Node, args: Dict) {
        val where = args.nestedDict(Constants.WHERE)
            ?.let { WhereInput.create(node, it) }

        val update = args.nestedDict(Constants.UPDATE_FIELD)
            ?.let { UpdateInput.NodeUpdateInput(node, it) }

        val connect = args.nestedDict(Constants.CONNECT_FIELD)
            ?.let { ConnectInput.NodeConnectInput(node, it) }

        val disconnect = args.nestedDict(Constants.DISCONNECT_FIELD)
            ?.let { DisconnectInput.NodeDisconnectInput(node, it) }

        val create = args.nestedDict(Constants.CREATE_FIELD)
            ?.let { RelationInput.create(node, it) }

        val delete = args.nestedDict(Constants.DELETE_FIELD)
            ?.let { DeleteInput.NodeDeleteInput(node, it) }

        val connectOrCreate = args.nestedDict(Constants.CONNECT_OR_CREATE_FIELD)
            ?.let { ConnectOrCreateInput.create(node, it) }
    }


    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()
        val dslNode = node.asCypherNode(queryContext, variable)
        val resolveTree = ResolveTree.resolve(env) // todo move into parent class

        val arguments = InputArguments(node, resolveTree.args)
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
                ChainString(schemaConfig, resolveTree.name, "args", "update"),
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
                ongoingReading = DisconnectTranslator(
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
                    ChainString(schemaConfig, resolveTree.name, "args", "disconnect", relField, nameClassifier),
                    ongoingReading.with(withVars)
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
                    includeRelationshipValidation = assumeReconnecting,
                    usePrefix = PrefixUsage.EXTEND
                ).createConnectAndParams()
            }
        }

        arguments.create?.relations?.forEach { (relField, input) ->
            relField.getReferenceNodes().forEach { refNode ->
                val creates: List<Pair<CreateInput?, ScalarProperties?>>? = when (input) {
                    is RelationFieldInput.NodeCreateCreateFieldInputs -> input.map { it.node to it.edge }
                    is RelationFieldInput.InterfaceCreateFieldInputs -> input.map { it.node?.getDataForNode(refNode) to it.edge }
                    is RelationFieldInput.UnionFieldInput -> input.getDataForNode(node)?.map { it.node to it.edge }
                }

                creates?.forEachIndexed { index, (createNode, createEdge) ->
                    val base = ChainString(
                        schemaConfig,
                        dslNode,
                        "create",
                        relField,
                        refNode.takeIf { relField.isInterface || relField.isUnion })
                        .appendOnPrevious(index)
                    val targetNode = refNode.asCypherNode(queryContext, base.extend("node").resolveName())
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
                        dslNode, targetNode, base.extend("relationship")
                    )
                    ongoingReading = (ongoingReading as ExposesMerge).merge(relationship)
                        .let { merge ->
                            relField.properties?.let { properties ->
                                createSetProperties(
                                    relationship,
                                    createEdge,
                                    Operation.CREATE,
                                    properties,
                                    schemaConfig,
                                    queryContext
                                )?.let { merge.set(it).with(dslNode) }
                            } ?: merge
                        }
                }
            }
        }

        arguments.delete?.let { deleteInput ->
            ongoingReading = createDeleteAndParams(
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
                    dslNode,
                    relField,
                    refNode,
                    withVars,
                    ongoingReading,
                    queryContext,
                    schemaConfig,
                    ChainString(schemaConfig, dslNode, "connectOrCreate", relField)
                )
            }

        }

        val projection = resolveTree.getFieldOfType(
            node.operations.mutationResponseTypeNames.update,
            node.plural
        )
            .firstOrNull()// TODO use all aliases
            ?.let {
                ProjectionTranslator()
                    .createProjectionAndParams(node, dslNode, it, null, schemaConfig, queryContext)
            }

        projection?.authValidate?.let {
            ongoingReading = ongoingReading
                .maybeWith(withVars)
                .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }

        if (arguments.update == null) {
            RelationshipValidationTranslator
                .createRelationshipValidations(node, dslNode, queryContext, schemaConfig)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    ongoingReading = ongoingReading
                        .maybeWith(withVars)
                        .withSubQueries(it)
                }
        }

        return ongoingReading
            .let {
                if (
                    arguments.delete != null ||
                    arguments.connect != null ||
                    arguments.disconnect != null ||
                    arguments.create != null ||
                    arguments.connectOrCreate != null ||
                    !projection?.allSubQueries.isNullOrEmpty()
                ) {
                    it.with(Cypher.asterisk()) // TODO remove this statement
                } else {
                    it
                }
            }
            .let {
                if (projection?.allSubQueries.isNullOrEmpty()) {
                    it
                } else {
                    it.maybeWith(withVars).withSubQueries(projection?.allSubQueries)
                }
            }
            .let {
                if (it is ExposesReturning) {
                    it
                } else {
                    it.maybeWithAsterix()
                }
            }
            .returning(
                *listOfNotNull(
                    projection?.let { Functions.collectDistinct(dslNode.project(it.projection)).`as`(Constants.DATA) },
//                    TODO subscription
                ).toTypedArray()
            )
            .build()
    }

}

