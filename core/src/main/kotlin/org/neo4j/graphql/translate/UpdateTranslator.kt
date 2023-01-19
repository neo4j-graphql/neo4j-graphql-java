package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.AuthableField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.update.UpdateFieldInput
import org.neo4j.graphql.schema.model.inputs.update.UpdateInput
import org.neo4j.graphql.translate.where.createConnectionWhere

class UpdateTranslator(
    private val parentVar: Node,
    private val updateInput: UpdateInput?,
    private val varName: Node,
    private val chainStr: ChainString?,
    private val node: org.neo4j.graphql.domain.Node,
    private val withVars: List<SymbolicName>,
    private val queryContext: QueryContext,
    private val parameterPrefix: ChainString,
    private val includeRelationshipValidation: Boolean = false,
    private val schemaConfig: SchemaConfig,
    private val ongoingReading: ExposesWith
) {

    fun createUpdateAndParams(): ExposesWith {
        if (updateInput == null) return ongoingReading

        var preAuthCondition =
            AuthTranslator(schemaConfig, queryContext, allow = AuthTranslator.AuthOptions(varName, node))
                .createAuth(node.auth, AuthDirective.AuthOperation.UPDATE)

        var postAuthCondition = AuthTranslator(
            schemaConfig,
            queryContext,
            bind = AuthTranslator.AuthOptions(varName, node),
            skipIsAuthenticated = true,
            skipRoles = true
        )
            .createAuth(node.auth, AuthDirective.AuthOperation.UPDATE)

        val prefix = chainStr ?: ChainString(schemaConfig, parentVar, "update")

        updateInput.properties?.forEach { field ->
            if (field is AuthableField && field.auth != null) {

                AuthTranslator(
                    schemaConfig,
                    queryContext,
                    allow = AuthTranslator.AuthOptions(parentVar, node, chainStr = prefix.extend(field))
                )
                    .createAuth(field.auth, AuthDirective.AuthOperation.UPDATE)
                    ?.let { preAuthCondition = preAuthCondition and it }

                AuthTranslator(
                    schemaConfig,
                    queryContext,
                    bind = AuthTranslator.AuthOptions(parentVar, node, chainStr = prefix.extend(field)),
                    skipIsAuthenticated = true,
                    skipRoles = true
                )
                    .createAuth(field.auth, AuthDirective.AuthOperation.UPDATE)
                    ?.let { postAuthCondition = postAuthCondition and it }

            }
        }

        var result = ongoingReading

        preAuthCondition?.let {
            result = result
                .maybeWith(withVars)
                .with(withVars) // TODO use maybeWith?
                .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }
        result = createSetPropertiesSplit(
            varName,
            updateInput.properties,
            Operation.UPDATE,
            node,
            schemaConfig,
            queryContext,
            prefix
        )
            // TODO use createSetProperties and ?.let { result.requiresExposeSet(withVars).set(it) } ?: result
            //  instead of:
            ?.let {
                var x = result

                for ((prop, value) in it) {
                    x = x.requiresExposeSet(withVars).set(prop, value)
                }
                x
            } ?: result


        updateInput.relations.forEach { (field, value) ->
            result = handleRelationField(field, value, prefix.extend(field), result)

            // TODO subscriptions
        }

        postAuthCondition?.let {
            result = result.maybeWith(withVars).apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }

        if (includeRelationshipValidation) {
            RelationshipValidationTranslator
                .createRelationshipValidations(node, varName, queryContext, schemaConfig)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    result = result
                        .maybeWith(withVars)
                        .withSubQueries(it)
                }
        }
        return result
    }

    private fun handleRelationField(
        field: RelationField,
        value: UpdateFieldInput,
        param: ChainString,
        subquery: ExposesWith,
    ): ExposesWith {
        val key = field.fieldName
        val refNodes = field.getReferenceNodes()
        var result = subquery
        refNodes.forEach { refNode ->

            val inputs = when (value) {
                is UpdateFieldInput.NodeUpdateFieldInputs -> value
                is UpdateFieldInput.InterfaceUpdateFieldInputs -> value
                is UpdateFieldInput.UnionUpdateFieldInput -> value.getDataForNode(refNode) ?: return@forEach
            }
            inputs.forEachIndexed { index, input ->
                val _varName = ChainString(schemaConfig,
                    varName,
                    key.let { it + (index.takeIf { !field.isUnion } ?: "") },
                    (refNode.name + index).takeIf { field.isUnion }
                )

                result = handleNestedUpdate(refNode, field, input, index, _varName, param)
                    ?.let { result.maybeWith(withVars).call(it) }
                    ?: result

                input.disconnect?.let {
                    result = DisconnectTranslator(
                        withVars,
                        it,
                        _varName.extend("disconnect"),
                        field,
                        parentVar,
                        queryContext,
                        schemaConfig,
                        listOf(refNode),
                        refNode.name.takeIf { field.isUnion },
                        node,
                        parameterPrefix.extend(
                            key,
                            refNode.takeIf { field.isUnion },
                            index.takeIf { field.typeMeta.type.isList() },
                            "disconnect"
                        ),
                        result
                    )
                        .createDisconnectAndParams()
                }

                input.connect?.let { connect ->
                    result = ConnectTranslator(
                        schemaConfig,
                        queryContext,
                        node,
                        _varName.extend("connect"),
                        parentVar,
                        fromCreate = false,
                        withVars,
                        field,
                        connect,
                        exposeWith = result,
                        listOf(refNode),
                        refNode.name.takeIf { field.isUnion },
                        includeRelationshipValidation = false
                    ).createConnectAndParams()
                }

                (input as? UpdateFieldInput.NodeUpdateFieldInput)?.connectOrCreate?.let { connectOrCreate ->
                    result = createConnectOrCreate(
                        connectOrCreate,
                        _varName.extend("connectOrCreate"),
                        varName,
                        field,
                        refNode,
                        withVars,
                        result,
                        queryContext,
                        schemaConfig
                    )
                }

                input.delete?.let { delete ->
                    TODO("createDisconnectAndParams 335")
                }

                input.create?.let { create ->
                    TODO("createDisconnectAndParams 355")
                }
            }
        }
        return result
    }

    private fun handleNestedUpdate(
        refNode: org.neo4j.graphql.domain.Node,
        field: RelationField,
        input: UpdateFieldInput.ImplementingTypeUpdateFieldInput,
        index: Int,
        _varName: ChainString,
        param: ChainString
    ): Statement? {
        val update = input.update ?: return null

        val relationshipVariable =
            ChainString(schemaConfig, varName, field.relationType.lowercase(), index, "relationship")

        val endNode = refNode.asCypherNode(queryContext, _varName)

        val rel = field.createDslRelation(
            Cypher.anyNode(parentVar.requiredSymbolicName),
            endNode,
            relationshipVariable,
            startLeft = true
        )

        val (whereCondition, whereSubquery) = createConnectionWhere(
            input.where,
            refNode,
            endNode,
            field,
            rel,
            parameterPrefix.extend(
                field,
                refNode.takeIf { field.isUnion },
                index.takeIf { field.typeMeta.type.isList() }),
            schemaConfig,
            queryContext,
        )

        val whereAuth = AuthTranslator(schemaConfig, queryContext, where = AuthTranslator.AuthOptions(endNode, refNode))
            .createAuth(refNode.auth, AuthDirective.AuthOperation.UPDATE)

        var result = Cypher
            .with(withVars)
            .match(rel)
            .optionalWhere(listOf(whereCondition, whereAuth))
            .withSubQueries(whereSubquery)
            .let { reading ->
                field.properties?.let { edgeProperties ->
                    update.edge?.let { edgeUpdate ->
                        createSetProperties(
                            rel,
                            edgeUpdate,
                            Operation.UPDATE,
                            edgeProperties,
                            schemaConfig,
                            queryContext,
                            parameterPrefix.extend(
                                field,
                                refNode.name.takeIf { field.isUnion },
                                index.takeIf { field.typeMeta.type.isList() },
                                "update",
                                "edge"
                            )
                        )
                            ?.let { reading.set(it) }
                    }
                } ?: reading
            }



        update.node?.let { nodeUpdate ->

            val nestedWithVars = withVars + endNode.requiredSymbolicName
            val (inputOnForNode, inputExcludingOnForNode) = when (nodeUpdate) {
                is UpdateInput.NodeUpdateInput -> null to nodeUpdate
                is UpdateInput.InterfaceUpdateInput -> nodeUpdate.on?.getDataForNode(refNode) to nodeUpdate.getCommonFields(
                    refNode
                )
            }

            result = UpdateTranslator(
                endNode,
                inputExcludingOnForNode,
                endNode,
                param.extend(
                    refNode.name.takeIf { field.isUnion },
                    index
                ),
                refNode,
                nestedWithVars,
                queryContext,
                param.extend(
                    field,
                    refNode.takeIf { field.isUnion },
                    index.takeIf { field.typeMeta.type.isList() },
                    "update",
                    "node"
                ),
                includeRelationshipValidation = true,
                schemaConfig,
                result,
            ).createUpdateAndParams()

            if (field.isInterface && inputOnForNode != null) {
                result = UpdateTranslator(
                    endNode,
                    inputOnForNode,
                    endNode,
                    param.extend(
                        refNode.name.takeIf { field.isUnion },
                        index,
                        "on",
                        refNode // TODO don't take for union
                    ),
                    refNode,
                    nestedWithVars,
                    queryContext,
                    parameterPrefix.extend(
                        field,
                        refNode.takeIf { field.isUnion },
                        index.takeIf { field.typeMeta.type.isList() },
                        "update",
                        "node",
                        "on",
                        refNode // TODO don't take for union
                    ),
                    includeRelationshipValidation = false,
                    schemaConfig,
                    result,
                ).createUpdateAndParams()
            }

//            result = result.maybeWith(withVars).call(
//                (subResult as ExposesReturning).returning(
//                    Functions.count(Cypher.asterisk()).`as`(
//                        param.extend(
//                            refNode.name.takeIf { field.isUnion },
//                            index,
//                            refNode.name
//                        ).resolveName(),
//                    )
//                ).build()
//            )

            // TODO subscription 232
        }

        return result
            .returning(
                Functions.count(Cypher.asterisk())
                    .`as`(ChainString(schemaConfig, "update", varName, refNode).resolveName())
            )
            .build()
    }
}
