package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.AuthableField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.update.UpdateFieldInput
import org.neo4j.graphql.domain.inputs.update.UpdateInput
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.translate.where.createConnectionWhere

class UpdateTranslator(
    private val parentVar: Node,
    private val updateInput: UpdateInput?,
    private val varName: Node,
    private val chainStr: ChainString?,
    private val node: org.neo4j.graphql.domain.Node,
    private val withVars: List<SymbolicName>,
    private val context: QueryContext,
    private val parameterPrefix: ChainString,
    private val includeRelationshipValidation: Boolean = false,
    private val schemaConfig: SchemaConfig,
    private val ongoingReading: ExposesWith
) {

    fun createUpdateAndParams(): ExposesWith {
        if (updateInput == null) return ongoingReading

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

        val prefix = chainStr ?: ChainString(schemaConfig, parentVar, "update")

        updateInput.properties?.forEach { field ->
            if (field is AuthableField && field.auth != null) {

                AuthTranslator(
                    schemaConfig,
                    context,
                    allow = AuthTranslator.AuthOptions(parentVar, node, chainStr = prefix.extend(field))
                )
                    .createAuth(field.auth, AuthDirective.AuthOperation.UPDATE)
                    ?.let { preAuthCondition = preAuthCondition and it }

                AuthTranslator(
                    schemaConfig,
                    context,
                    bind = AuthTranslator.AuthOptions(parentVar, node, chainStr = prefix.extend(field)),
                    skipIsAuthenticated = true,
                    skipRoles = true
                )
                    .createAuth(field.auth, AuthDirective.AuthOperation.UPDATE)
                    ?.let { postAuthCondition = postAuthCondition and it }

            }
        }

        var result = ongoingReading

        preAuthCondition?.let { result = result.maybeWith(withVars).apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR) }
        result = createSetProperties(
            varName,
            updateInput.properties,
            Operation.UPDATE,
            node,
            schemaConfig,
            prefix
        )
            ?.let { result.requiresExposeSet(withVars).set(it) } ?: result


        updateInput.relations.forEach { (field, value) ->
            result = handleRelationField(field, value, prefix.extend(field), result)

            // TODO subscriptions
        }

        postAuthCondition?.let {
            result = result.maybeWith(withVars).apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
        }

        if (includeRelationshipValidation) {
            RelationshipValidationTranslator
                .createRelationshipValidations(node, varName, context, schemaConfig)
                .takeIf { it.isNotEmpty() }
                ?.let { result = result.maybeWith(withVars).withSubQueries(it) }
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

                input.update?.let { update ->
                    result = handleNestedUpdate(refNode, field, input, update, index, _varName, result, param)
                }

                input.disconnect?.let {
                    result = DisconnectTranslator(
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

                input.connect?.let { connect ->
                    result = ConnectTranslator(
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
                        context,
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
        update: UpdateFieldInput.ImplementingTypeUpdateConnectionInput,
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

        input.where?.let { where ->
            val (whereCondition, whereSubquery) = createConnectionWhere(
                where,
                refNode,
                endNode,
                field,
                rel,
                parameterPrefix.extend(
                    field,
                    refNode.takeIf { field.isUnion },
                    index.takeIf { field.typeMeta.type.isList() }),
                schemaConfig,
                context,
            )
            whereCondition?.let { condition = condition and it }
            if (whereSubquery.isNotEmpty()){
                TODO()
            }
        }

        AuthTranslator(schemaConfig, context, where = AuthTranslator.AuthOptions(endNode, refNode))
            .createAuth(refNode.auth, AuthDirective.AuthOperation.UPDATE)
            ?.let { condition = condition and it }

        result = if (withVars.isNotEmpty()) {
            result.with(withVars).optionalMatch(rel)
        } else {
            (result as ExposesMatch).optionalMatch(rel)
        }.let { if (condition != null) it.where(condition) else it }

        update.node?.let { nodeUpdate ->

            val nestedWithVars = listOf(endNode.requiredSymbolicName)
            val (inputOnForNode, inputExcludingOnForNode) = when (nodeUpdate) {
                is UpdateInput.NodeUpdateInput -> null to nodeUpdate
                is UpdateInput.InterfaceUpdateInput -> nodeUpdate.on?.getDataForNode(refNode) to nodeUpdate.getCommonFields(
                    refNode
                )
            }

            var subResult = UpdateTranslator(
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
                subResult = UpdateTranslator(
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

            result = (result as StatementBuilder.OngoingReading).call(
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
            update.edge?.let { edgeUpdate ->
                result = createSetProperties(
                    rel,
                    edgeUpdate,
                    Operation.UPDATE,
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
                    ?.let { exposesWith.requiresExposeSet(listOf(rel.requiredSymbolicName)).set(it) }
                    ?: result
            }
        }
        return result
    }
}
