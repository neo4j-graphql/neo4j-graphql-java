package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.translate.where.CreateConnectionWhere
import org.neo4j.graphql.utils.InterfaceInputUtils

//TODO complete
class CreateDisconnectTranslator(
    private val withVars: List<SymbolicName>,
    private val value: Any?,
    private val varName: String,
    private val relationField: RelationField,
    private val parentVar: Node,
    private val context: QueryContext?,
    private val schemaConfig: SchemaConfig,
    private val refNodes: List<org.neo4j.graphql.domain.Node>,
    private val labelOverride: String? = null,
    private val parentNode: org.neo4j.graphql.domain.Node,
    private val parameterPrefix: String,
    private val ongoingReading: ExposesWith,

    ) {

    fun createDisconnectAndParams(): ExposesWith {
        val list = value as? List<*> ?: listOf(value)
        val whereAuth = AuthTranslator(schemaConfig, context, where = AuthTranslator.AuthOptions(parentVar, parentNode))
            .createAuth(parentNode.auth, AuthDirective.AuthOperation.DISCONNECT)
        var result = ongoingReading

        list.forEachIndexed { index, disconnect ->

            val subqueries = if (relationField.isInterface) {
                refNodes.map { refNode -> createSubqueryContents(refNode, disconnect, index) }
            } else {
                listOf(createSubqueryContents(refNodes.first(), disconnect, index))
            }

            // TODO is union for interfaces required?

            result = (whereAuth?.let { result.with(withVars).where(it) } ?: result)
                .with(withVars)
                .withSubQueries(subqueries)
        }
        return result
    }

    fun createSubqueryContents(
        relatedNode: org.neo4j.graphql.domain.Node,
        input: Any?,
        index: Int,
    ): Statement {
        val _varName = schemaConfig.namingStrategy.resolveName(varName, index)

        val relVarName = schemaConfig.namingStrategy.resolveName(_varName, "rel")

        val endNode =
            labelOverride?.let { Cypher.node(it).named(_varName) } ?: relatedNode.asCypherNode(context, _varName)
        val dslRelation = relationField.createDslRelation(parentVar, endNode, relVarName)

        var condition: Condition? = null
        input.nestedObject(Constants.WHERE)?.let { where ->
            condition = CreateConnectionWhere(schemaConfig, context)
                .createConnectionWhere(
                    where,
                    relatedNode,
                    endNode,
                    relationField,
                    dslRelation,
                    schemaConfig.namingStrategy.resolveName(
                        parameterPrefix,
                        index.takeIf { relationField.typeMeta.type.isList() },
                        "where"
                    )
                )
        }

        if (relatedNode.auth != null) {
            AuthTranslator(schemaConfig, context, where = AuthTranslator.AuthOptions(endNode, relatedNode))
                .createAuth(relatedNode.auth, AuthDirective.AuthOperation.DISCONNECT)
                ?.let { condition = condition and it }
        }

        var preAuth: Condition? = null
        var postAuth: Condition? = null
        listOf(
            AuthTranslator.AuthOptions(parentVar, parentNode),
            AuthTranslator.AuthOptions(endNode, relatedNode)
        )
            .filter { it.parentNode.auth != null }
            .forEachIndexed { i, authOptions ->
                AuthTranslator(
                    schemaConfig,
                    context,
                    allow = authOptions.copy(
                        chainStr = schemaConfig.namingStrategy.resolveName(
                            authOptions.varName,
                            authOptions.parentNode,
                            i,
                            "allow"
                        )
                    )
                )
                    .createAuth(authOptions.parentNode.auth, AuthDirective.AuthOperation.DISCONNECT)
                    ?.let { preAuth = preAuth and it }

                AuthTranslator(
                    schemaConfig,
                    context,
                    skipRoles = true,
                    skipIsAuthenticated = true,
                    bind = authOptions.copy(
                        varName = endNode, // TODO is this intentional? create a test for this!
                        chainStr = schemaConfig.namingStrategy.resolveName(
                            authOptions.varName,
                            authOptions.parentNode,
                            i,
                            "bind"
                        )
                    )
                )
                    .createAuth(authOptions.parentNode.auth, AuthDirective.AuthOperation.DISCONNECT)
                    ?.let { postAuth = postAuth and it }
            }


        var call: ExposesWith = Cypher.with(withVars)
            .optionalMatch(dslRelation)
            .let { query ->
                condition?.let { query.where(condition) } ?: query
            }
            .let { query ->
                preAuth?.let { query.apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR) } ?: query
            }
            .call(
                Cypher.with(endNode, dslRelation)
                    .with(endNode, dslRelation)
                    .where(endNode.isNotNull)
                    .delete(dslRelation)
                    .build()
            )

        input.nestedObject(Constants.DISCONNECT_FIELD)?.let {
            val disconnects = it as? List<*> ?: listOf(it)

            val nestedWithVars = withVars + endNode.requiredSymbolicName
            disconnects.forEachIndexed { i, c ->
                val (inputOnForNode, inputExcludingOnForNode) = InterfaceInputUtils
                    .spiltInput(c as? Map<*, *>, relatedNode.name, relationField.isInterface)


                inputExcludingOnForNode.forEach { (k, v) ->
                    // TODO why in js startsWith is used for getting the field (line 193)?
                    val relField = relatedNode.getField(k as String) as? RelationField ?: return@forEach

                    val newRefNodes = if (relField.isUnion) {
                        (v as Map<*, *>).keys.mapNotNull { relField.getNode(it as String) }
                    } else {
                        relField.getReferenceNodes()
                    }

                    newRefNodes.forEach { newRefNode ->
                        call = CreateDisconnectTranslator(
                            nestedWithVars,
                            if (relField.isUnion) v.nestedObject(newRefNode.name) else v,
                            schemaConfig.namingStrategy.resolveName(
                                endNode,
                                k,
                                newRefNode.name.takeIf { relField.isUnion }),
                            relField,
                            endNode,
                            context, schemaConfig,
                            listOf(newRefNode),
                            labelOverride = newRefNode.name.takeIf { relField.isUnion },
                            relatedNode,
                            schemaConfig.namingStrategy.resolveName(
                                parameterPrefix,
                                i.takeIf { relField.typeMeta.type.isList() },
                                "disconnect",
                                k,
                                newRefNode.name.takeIf { relField.isUnion }),
                            call,
                        )
                            .createDisconnectAndParams()
                    }

                    if (relationField.isInterface && inputOnForNode != null) {
                        // TODO harmonize with block above
                        val onDisconnects = inputOnForNode as? List<*> ?: listOf(inputOnForNode)
                        onDisconnects.forEachIndexed { onDisconnectIndex, onDisconnect ->
                            (onDisconnect as? Map<*, *>)?.forEach { (k, v) ->
                                newRefNodes.forEach { newRefNode ->
                                    call = CreateDisconnectTranslator(
                                        nestedWithVars,
                                        if (relField.isUnion) v.nestedObject(newRefNode.name) else v,
                                        schemaConfig.namingStrategy.resolveName(
                                            endNode,
                                            k,
                                            newRefNode.name.takeIf { relField.isUnion }),
                                        relField,
                                        endNode,
                                        context, schemaConfig,
                                        listOf(newRefNode),
                                        labelOverride = newRefNode.name.takeIf { relField.isUnion },
                                        relatedNode,
                                        schemaConfig.namingStrategy.resolveName(
                                            parameterPrefix,
                                            i.takeIf { relField.typeMeta.type.isList() },
                                            "disconnect",
                                            "_on",
                                            newRefNode.name,
                                            onDisconnectIndex.takeIf { relField.typeMeta.type.isList() },
                                            k,
                                            newRefNode.name.takeIf { relField.isUnion }),
                                        call,
                                    )
                                        .createDisconnectAndParams()
                                }

                            }

                        }
                    }
                }
            }
        }

        return (call as OngoingReading)
            .let { query ->
                postAuth?.let { query.apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR) } ?: query
            }
            .returning(Functions.count(Cypher.asterisk()))
            .build()
    }
}
