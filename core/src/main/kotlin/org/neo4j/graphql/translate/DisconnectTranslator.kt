package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.connection_where.ConnectionWhere
import org.neo4j.graphql.domain.inputs.disconnect.DisconnectFieldInput
import org.neo4j.graphql.domain.inputs.disconnect.DisconnectInput
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.translate.where.createConnectionWhere

//TODO complete
class DisconnectTranslator(
    private val withVars: List<SymbolicName>,
    private val inputs: List<DisconnectFieldInput.ImplementingTypeDisconnectFieldInput>,
    private val varName: ChainString,
    private val relationField: RelationField,
    private val parentVar: Node,
    private val context: QueryContext?,
    private val schemaConfig: SchemaConfig,
    private val refNodes: Collection<org.neo4j.graphql.domain.Node>,
    private val labelOverride: String? = null,
    private val parentNode: org.neo4j.graphql.domain.Node,
    private val parameterPrefix: ChainString,
    private val ongoingReading: ExposesWith,
) {

    fun createDisconnectAndParams(): ExposesWith {
        val whereAuth = AuthTranslator(schemaConfig, context, where = AuthTranslator.AuthOptions(parentVar, parentNode))
            .createAuth(parentNode.auth, AuthDirective.AuthOperation.DISCONNECT)
        var result = ongoingReading

        inputs.forEachIndexed { index, disconnect ->

            val subqueries = if (relationField.isInterface) {
                refNodes.map { refNode -> createSubqueryContents(refNode, disconnect, index) }
            } else {
                listOf(createSubqueryContents(refNodes.first(), disconnect, index))
            }

            // TODO is cypher union for an interfaces required?

            result = (whereAuth?.let { result.with(withVars).where(it) } ?: result)
                .with(withVars)
                .withSubQueries(subqueries)
        }
        return result
    }

    private fun createSubqueryContents(
        relatedNode: org.neo4j.graphql.domain.Node,
        input: DisconnectFieldInput.ImplementingTypeDisconnectFieldInput,
        index: Int,
    ): Statement {
        val nestedPrefix = varName.extend(index)
        val relVarName = nestedPrefix.extend("rel")

        val endNode = labelOverride
            ?.let { Cypher.node(it).named(nestedPrefix.resolveName()) }
            ?: relatedNode.asCypherNode(context, nestedPrefix)

        val dslRelation = relationField.createDslRelation(parentVar, endNode, relVarName)

        var condition: Condition? = null
        input.where?.let { where ->
            condition = createConnectionWhere(
                where as? ConnectionWhere.ImplementingTypeConnectionWhere ?: TODO("handle unions"),
                relatedNode,
                endNode,
                relationField,
                dslRelation,
                parameterPrefix.extend(
                    index.takeIf { relationField.typeMeta.type.isList() },
                    "where"
                ),
                schemaConfig,
                context,
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
                        chainStr = ChainString(
                            schemaConfig,
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
                        chainStr = ChainString(
                            schemaConfig,
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
                    .with(endNode, dslRelation)// TODO use the new version from js
                    .where(endNode.isNotNull)
                    .delete(dslRelation)
                    .returning(Functions.count(Cypher.asterisk()))
                    .build()
            )

        input.disconnect?.let { disconnects ->
            val nestedWithVars = withVars + endNode.requiredSymbolicName
            disconnects.forEachIndexed { i, c ->
                val (inputOnForNode, inputExcludingOnForNode) = when (c) {
                    is DisconnectInput.NodeDisconnectInput -> null to c
                    is DisconnectInput.InterfaceDisconnectInput -> c.on?.getDataForNode(relatedNode) to c.getCommonFields(
                        relatedNode
                    )
                }

                fun nestedDisconnect(
                    relField: RelationField,
                    value: DisconnectFieldInput,
                    classifier: ((RelationField, org.neo4j.graphql.domain.Node) -> ChainString)? = null
                ) {

                    val newRefNodes = if (value is DisconnectFieldInput.UnionDisconnectFieldInput) {
                        value.dataPerNode.keys
                    } else {
                        relField.getReferenceNodes()
                    }

                    newRefNodes.forEach { newRefNode ->

                        val inputs = when (value) {
                            is DisconnectFieldInput.NodeDisconnectFieldInputs -> value
                            is DisconnectFieldInput.UnionDisconnectFieldInput -> value.getDataForNode(newRefNode) ?: return
                            else -> throw IllegalStateException("unknown value type")
                        }

                        call = DisconnectTranslator(
                            nestedWithVars,
                            inputs,
                            ChainString(schemaConfig, endNode, relField, newRefNode.takeIf { relField.isUnion }),
                            relField,
                            endNode,
                            context, schemaConfig,
                            listOf(newRefNode),
                            labelOverride = newRefNode.name.takeIf { relField.isUnion },
                            relatedNode,
                            parameterPrefix.extend(
                                i.takeIf { relField.typeMeta.type.isList() },
                                "disconnect",
                                classifier?.let { it(relField, newRefNode) },
                                relField,
                                newRefNode.takeIf { relField.isUnion }),
                            call,
                        )
                            .createDisconnectAndParams()
                    }
                }

                inputExcludingOnForNode.relations.forEach { (k, v) -> nestedDisconnect(k, v) }

                inputOnForNode?.forEachIndexed { onDisconnectIndex, onDisconnect ->
                    onDisconnect.relations.forEach { (k, v) ->

                        nestedDisconnect(k, v) { relField, newRefNode ->
                            ChainString(
                                schemaConfig,
                                "_on",
                                newRefNode,
                                onDisconnectIndex.takeIf { relField.typeMeta.type.isList() },
                            )
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
