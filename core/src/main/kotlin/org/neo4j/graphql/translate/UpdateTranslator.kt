package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.dto.*
import org.neo4j.graphql.domain.fields.AuthableField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.translate.where.createConnectionWhere

class UpdateTranslator(
    private val parentVar: Node,
    private val updateInput: UpdateInput?,
    private val varName: Node,
    private val chainStr: String?,
    private val node: org.neo4j.graphql.domain.Node,
    private val withVars: List<SymbolicName>,
    private val context: QueryContext?,
    private val parameterPrefix: String,
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

        val prefix = if (chainStr != null) {
            schemaConfig.namingStrategy.resolveName(chainStr)
        } else {
            schemaConfig.namingStrategy.resolveName(parentVar, "update")
        }

        fun getParam(key: Any) = schemaConfig.namingStrategy.resolveName(prefix, key)


        updateInput.fields().forEach { field ->
            if (field is AuthableField && field.auth != null) {

                AuthTranslator(
                    schemaConfig,
                    context,
                    allow = AuthTranslator.AuthOptions(parentVar, node, chainStr = getParam(field))
                )
                    .createAuth(field.auth, AuthDirective.AuthOperation.UPDATE)
                    ?.let { preAuthCondition = preAuthCondition and it }

                AuthTranslator(
                    schemaConfig,
                    context,
                    bind = AuthTranslator.AuthOptions(parentVar, node, chainStr = getParam(field)),
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
            .takeIf { it.isNotEmpty() }
            ?.let { result.requiresExposeSet(withVars).set(it) } ?: result


        updateInput.relations?.forEach { (field, value) ->
            result = handleRelationField(field, value, getParam(field), result)

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
        value: UpdateFieldInputs,
        param: String,
        subquery: ExposesWith,
    ): ExposesWith {
        val key = field.fieldName
        val refNodes = field.getReferenceNodes()
        var result = subquery
        refNodes.forEach { refNode ->

            val inputs = when (value) {
                is UpdateFieldInputs.NodeUpdateFieldInputs -> value
                is UpdateFieldInputs.UnionUpdateFieldInputs -> value[refNode] ?: return@forEach
                else -> throw IllegalStateException("unknown value type")
            }
            inputs.forEachIndexed { index, input ->
                val _varName = schemaConfig.namingStrategy.resolveName(
                    varName.name(),
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
                        schemaConfig.namingStrategy.resolveName(_varName, "disconnect"),
                        field,
                        parentVar,
                        context,
                        schemaConfig,
                        listOf(refNode),
                        refNode.name.takeIf { field.isUnion },
                        node,
                        schemaConfig.namingStrategy.resolveParameter(
                            parameterPrefix,
                            key,
                            refNode.name.takeIf { field.isUnion },
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
                        schemaConfig.namingStrategy.resolveName(_varName, "connect"),
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

                input.connectOrCreate?.let { connectOrCreate ->
                    result = createConnectOrCreate(
                        connectOrCreate,
                        schemaConfig.namingStrategy.resolveName(_varName, "connectOrCreate"),
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
        input: UpdateFieldInput,
        update: UpdateConnectionInput,
        index: Int,
        _varName: String,
        exposesWith: ExposesWith,
        param: String
    ): ExposesWith {
        var result = exposesWith

        val relationshipVariable = schemaConfig.namingStrategy
            .resolveName(varName, field.relationType.lowercase(), index, "relationship")

        val endNode = refNode.asCypherNode(context, _varName)

        val rel = field.createDslRelation(
            Cypher.anyNode(parentVar.requiredSymbolicName),
            endNode,
            relationshipVariable
        )
        var condition: Condition? = null

        input.where?.let { where ->
            createConnectionWhere(
                where as? ConnectionWhere.ImplementingTypeConnectionWhere
                    ?: throw IllegalArgumentException("only `where` for nodes are expected"),
                refNode,
                endNode,
                field,
                rel,
                schemaConfig.namingStrategy.resolveParameter(
                    parameterPrefix,
                    field.fieldName,
                    refNode.name.takeIf { field.isUnion },
                    index.takeIf { field.typeMeta.type.isList() }),
                schemaConfig,
                context,
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

        update.node?.let { nodeUpdate ->


            val nestedWithVars = listOf(endNode.requiredSymbolicName)

            val inputExcludingOnForNode = nodeUpdate.getCommonInterfaceFields(refNode)
            val inputOnForNode = nodeUpdate.getAdditionalFieldsForImplementation(refNode)

            var subResult = UpdateTranslator(
                endNode,
                inputExcludingOnForNode,
                endNode,
                schemaConfig.namingStrategy.resolveName(
                    param,
                    refNode.name.takeIf { field.isUnion },
                    index
                ),
                refNode,
                nestedWithVars,
                context,
                schemaConfig.namingStrategy.resolveName(
                    param,
                    field,
                    refNode.name.takeIf { field.isUnion },
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
                    schemaConfig.namingStrategy.resolveName(
                        param,
                        refNode.name.takeIf { field.isUnion },
                        index,
                        "on",
                        refNode.name
                    ),
                    refNode,
                    nestedWithVars,
                    context,
                    schemaConfig.namingStrategy.resolveName(
                        parameterPrefix,
                        field,
                        refNode.name.takeIf { field.isUnion },
                        index.takeIf { field.typeMeta.type.isList() },
                        "update",
                        "node",
                        "on",
                        refNode.name
                    ),
                    includeRelationshipValidation = false,
                    schemaConfig,
                    subResult,
                ).createUpdateAndParams()
            }

            result = (result as OngoingReading).call(
                (subResult as ExposesReturning).returning(
                    Functions.count(Cypher.asterisk()).`as`(
                        schemaConfig.namingStrategy.resolveName(
                            param,
                            refNode.name.takeIf { field.isUnion },
                            index,
                            refNode.name,
                            "ignore"
                        ),
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
                    schemaConfig.namingStrategy.resolveName(
                        parameterPrefix,
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
