package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.AuthableField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.translate.where.CreateConnectionWhere
import org.neo4j.graphql.utils.InterfaceInputUtils

class CreateUpdate(
    private val parentVar: Node,
    private val updateInputAny: Any?,
    private val varName: Node,
    private val chainStr: ChainString?,
    private val node: org.neo4j.graphql.domain.Node,
    private val withVars: List<SymbolicName>,
    private val context: QueryContext?,
    private val parameterPrefix: ChainString,
    private val includeRelationshipValidation: Boolean = false,
    private val schemaConfig: SchemaConfig,
    private val ongoingReading: ExposesWith
) {

    fun createUpdateAndParams(): ExposesWith {
        val updateInput = updateInputAny as? Map<*, *> ?: return ongoingReading

        var preAuthCondition = AuthTranslator(schemaConfig, context, allow = AuthTranslator.AuthOptions(varName, node))
            .createAuth(node.auth, AuthDirective.AuthOperation.UPDATE)

        var postAuthCondition = AuthTranslator(
            schemaConfig,
            context,
            bind = AuthTranslator.AuthOptions(varName, node),
            skipIsAuthenticated = true,
            skipRoles = true
        )
            .createAuth(node.auth, AuthDirective.AuthOperation.UPDATE)

        val param = chainStr?:ChainString(schemaConfig, parentVar, "update")

        updateInput.forEach { (key, value) ->
            val field = node.getField(key as String) ?: return@forEach

            if (field is AuthableField && field.auth != null) {

                AuthTranslator(
                    schemaConfig,
                    context,
                    allow = AuthTranslator.AuthOptions(parentVar, node, chainStr = param.extend(key))
                )
                    .createAuth(field.auth, AuthDirective.AuthOperation.UPDATE)
                    ?.let { preAuthCondition = preAuthCondition and it }

                AuthTranslator(
                    schemaConfig,
                    context,
                    bind = AuthTranslator.AuthOptions(parentVar, node, chainStr = param.extend(key)),
                    skipIsAuthenticated = true,
                    skipRoles = true
                )
                    .createAuth(field.auth, AuthDirective.AuthOperation.UPDATE)
                    ?.let { postAuthCondition = postAuthCondition and it }

            }
        }

        var result = ongoingReading

        preAuthCondition?.let { result = result.maybeWith(withVars).apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR) }
        result = CreateSetPropertiesTranslator
            .createSetProperties(
                varName,
                updateInput,
                CreateSetPropertiesTranslator.Operation.UPDATE,
                node,
                schemaConfig,
                param
            )
            .takeIf { it.isNotEmpty() }
            ?.let { result.requiresExposeSet(withVars).set(it) } ?: result


        updateInput.forEach { (key, value) ->
            val field = node.getField(key as String) ?: return@forEach

            if (field is RelationField) {
                result = handleRelationField(field, value, param.extend(key), result)
                return@forEach
            }

            // TODO subscriptions
        }

        postAuthCondition?.let {
            result = result.maybeWith(withVars).apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }

        if (includeRelationshipValidation) {
            CreateRelationshipValidationTranslator
                .createRelationshipValidations(node, varName, context, schemaConfig)
                .takeIf { it.isNotEmpty() }
                ?.let { result = result.with(withVars).withSubQueries(it) }
        }
        return result
    }

    private fun handleRelationField(
        field: RelationField,
        value: Any?,
        param: ChainString,
        subquery: ExposesWith,
    ): ExposesWith {
        val key = field.fieldName
        val refNodes = field.getReferenceNodes()
        var result = subquery
        refNodes.forEach { refNode ->

            val v = if (field.isUnion) {
                (value as? Map<*, *>)?.get(refNode.name)
            } else {
                value
            }
            // TODO we should check the type of v instead of the fields type meta
            val inputs = if (field.typeMeta.type.isList()) v as List<*> else listOf(v)

            inputs.forEachIndexed { index, inputAny ->
                val input = inputAny as Map<*, *>
                val _varName = ChainString(schemaConfig,
                    varName,
                    key.let { it + (index.takeIf { !field.isUnion } ?: "") },
                    (refNode.name + index).takeIf { field.isUnion }
                )

                input.nestedMap(Constants.UPDATE_FIELD)?.let { update ->
                    result = handleNestedUpdate(refNode, field, input, update, index, _varName, result, param)
                }

                input.nestedMap(Constants.DISCONNECT_FIELD)?.let {
                    result = CreateDisconnectTranslator(
                        withVars,
                        it,
                        _varName.extend("disconnect"),
                        field,
                        parentVar,
                        context,
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

                input.nestedMap(Constants.CONNECT_FIELD)?.let { connect ->
                    result = CreateConnectTranslator.createConnectAndParams(
                        schemaConfig,
                        context,
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
                    )
                }

                input.nestedMap(Constants.CONNECT_OR_CREATE_FIELD)?.let {
                    TODO("createDisconnectAndParams 321")
                }

                input.nestedMap(Constants.DELETE_FIELD)?.let {
                    TODO("createDisconnectAndParams 335")
                }

                input.nestedMap(Constants.CREATE_FIELD)?.let {
                    TODO("createDisconnectAndParams 355")
                }
            }
        }
        return result
    }

    private fun handleNestedUpdate(
        refNode: org.neo4j.graphql.domain.Node,
        field: RelationField,
        input: Map<*, *>,
        update: Map<*, *>,
        index: Int,
        _varName: ChainString,
        exposesWith: ExposesWith,
        param: ChainString
    ): ExposesWith {
        var result = exposesWith

        val relationshipVariable =
            ChainString(schemaConfig, varName, field.relationType.lowercase(), index, "relationship")

        val endNode = refNode.asCypherNode(context, _varName)

        val rel = field.createDslRelation(
            Cypher.anyNode(parentVar.requiredSymbolicName),
            endNode,
            relationshipVariable
        )
        var condition: Condition? = null

        input[Constants.WHERE]?.let { whereAny ->
            CreateConnectionWhere(schemaConfig, context)
                .createConnectionWhere(
                    whereAny,
                    refNode,
                    endNode,
                    field,
                    rel,
                    parameterPrefix.extend(
                        field,
                        refNode.takeIf { field.isUnion },
                        index.takeIf { field.typeMeta.type.isList() }
                    )
                )
                ?.let { condition = condition and it }
        }

        AuthTranslator(schemaConfig, context, where = AuthTranslator.AuthOptions(endNode, refNode))
            .createAuth(refNode.auth, AuthDirective.AuthOperation.UPDATE)
            ?.let { condition = condition and it }

        result = if (withVars.isNotEmpty()) {
            result.with(withVars).optionalMatch(rel)
        } else {
            (result as ExposesMatch).optionalMatch(rel)
        }.let { if (condition != null) it.where(condition) else it }

        update.nestedMap(Constants.NODE_FIELD)?.let { nodeUpdate ->


            val nestedWithVars = listOf(endNode.requiredSymbolicName)

            val (inputOnForNode, inputExcludingOnForNode) = InterfaceInputUtils
                .spiltInput(nodeUpdate, refNode.name, field.isInterface)

            var subResult = CreateUpdate(
                endNode,
                inputExcludingOnForNode,
                endNode,
                param.extend(
                    refNode.name.takeIf { field.isUnion },
                    index
                ),
                refNode,
                nestedWithVars,
                context,
                param.extend(
                    field,
                    refNode.takeIf { field.isUnion },
                    index.takeIf { field.typeMeta.type.isList() },
                    "update",
                    "node"
                ),
                includeRelationshipValidation = true,
                schemaConfig,
                Cypher.with(nestedWithVars),
            ).createUpdateAndParams()

            if (field.isInterface && inputOnForNode != null) {
                subResult = CreateUpdate(
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
                    context,
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
                    subResult,
                ).createUpdateAndParams()
            }

            result = (result as OngoingReading).call(
                (subResult as ExposesReturning).returning(
                    Functions.count(Cypher.asterisk()).`as`(
                        param.extend(
                            refNode.name.takeIf { field.isUnion },
                            index,
                            refNode.name,
                            "ignore"
                        ).resolveName(),
                    )
                ).build()
            )


            // TODO subscription 232
        }

        field.properties?.let { edgeProperties ->
            update.nestedMap(Constants.EDGE_FIELD)?.let { edgeUpdate ->
                result = CreateSetPropertiesTranslator
                    .createSetProperties(
                        rel,
                        edgeUpdate,
                        CreateSetPropertiesTranslator.Operation.UPDATE,
                        edgeProperties,
                        schemaConfig,
                        parameterPrefix.extend(
                            field,
                            refNode.name.takeIf { field.isUnion },
                            index.takeIf { field.typeMeta.type.isList() },
                            "update",
                            "edge"
                        )
                    )
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        exposesWith.requiresExposeSet(listOf(rel.requiredSymbolicName))
                            .set(it)
                    } ?: result
            }
        }
        return result
    }
}
