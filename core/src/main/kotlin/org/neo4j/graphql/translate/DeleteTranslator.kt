package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.inputs.delete.DeleteFieldInput
import org.neo4j.graphql.domain.inputs.delete.DeleteInput
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.translate.where.createConnectionWhere

fun createDeleteAndParams(
    node: Node,
    deleteInput: DeleteInput,
    varName: ChainString,
    parentVar: org.neo4j.cypherdsl.core.Node,
    withVars: List<SymbolicName>,
    parameterPrefix: ChainString,
    schemaConfig: SchemaConfig,
    queryContext: QueryContext?,
    ongoingReading: ExposesWith,
    nested: Boolean = false
): ExposesWith {

    var exposesWith: ExposesWith = ongoingReading
    deleteInput.relations.forEach { (relField, input) ->
        relField.getReferenceNodes().forEach { refNode ->
            val deletes = when (input) {
                is DeleteFieldInput.NodeDeleteFieldInputs -> input
                is DeleteFieldInput.InterfaceDeleteFieldInputs -> input
                is DeleteFieldInput.UnionDeleteFieldInput -> input.getDataForNode(refNode)
            }

            deletes?.forEachIndexed { index, delete ->
                val endNodeName = varName.extend(
                    relField,
                    refNode.takeIf { (relField.isUnion || relField.isInterface) },
                    index
                )

                val endNode = refNode.asCypherNode(queryContext, endNodeName)
                val dslRelation = relField.createDslRelation(
                    parentVar, endNode,
                    endNodeName.extend("relationship")
                )

                var condition = createConnectionWhere(
                    delete.where,
                    refNode,
                    endNode,
                    relField,
                    dslRelation,
                    parameterPrefix.extend(
                        relField.takeIf { !nested },
                        refNode.takeIf { relField.isUnion },
                        index.takeIf { relField.typeMeta.type.isList() },
                        "where"
                    ),
                    schemaConfig,
                    queryContext
                )

                AuthTranslator(schemaConfig, queryContext, where = AuthTranslator.AuthOptions(endNode, refNode))
                    .createAuth(refNode.auth, AuthDirective.AuthOperation.DELETE)
                    ?.let { condition = condition and it }

                exposesWith = exposesWith.with(withVars)
                    .optionalMatch(dslRelation)
                    .where(condition)
                    .let { reading ->
                        AuthTranslator(
                            schemaConfig,
                            queryContext,
                            allow = AuthTranslator.AuthOptions(endNode, refNode)
                        )
                            .createAuth(refNode.auth, AuthDirective.AuthOperation.DELETE)
                            ?.let {
                                reading
                                    .with(withVars + endNode.requiredSymbolicName)
                                    .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
                            }
                            ?: reading
                    }

                val nestedDelete = delete.delete
                if (nestedDelete != null) {

                    val (inputOnForNode, inputExcludingOnForNode) = when (nestedDelete) {
                        is DeleteInput.NodeDeleteInput -> null to nestedDelete
                        is DeleteInput.InterfaceDeleteInput -> nestedDelete.on?.getDataForNode(refNode) to nestedDelete.getCommonFields(
                            refNode
                        )
                    }

                    exposesWith = createDeleteAndParams(
                        refNode,
                        inputExcludingOnForNode,
                        endNodeName,
                        endNode,
                        withVars + endNode.requiredSymbolicName,
                        parameterPrefix.extend(
                            relField.takeIf { !nested },
                            refNode.takeIf { relField.isUnion },
                            index.takeIf { relField.typeMeta.type.isList() },
                            "delete"
                        ),
                        schemaConfig,
                        queryContext,
                        exposesWith,
                        nested = true
                    )

                    if (relField.isInterface) {
                        TODO()
                    }
                    inputOnForNode?.forEachIndexed { onDeleteIndex, onDelete ->
                        exposesWith = createDeleteAndParams(
                            refNode,
                            onDelete,
                            endNodeName,
                            endNode,
                            withVars + endNode.requiredSymbolicName,
                            parameterPrefix.extend(
                                relField.takeIf { !nested },
                                index.takeIf { relField.typeMeta.type.isList() },
                                "delete",
                                "_on",
                                refNode,
                                onDeleteIndex
                            ),
                            schemaConfig,
                            queryContext,
                            exposesWith,
                            nested = true
                        )
                    }
                }

                // TODO subscriptions

                val nodeToDelete = Cypher.name(endNodeName.extend("to", "delete").resolveName())

                val x = Cypher.name("x")
                exposesWith.with(withVars + Functions.collectDistinct(endNode).`as`(nodeToDelete))
                    .call(
                        Cypher.with(nodeToDelete)
                            .unwind(nodeToDelete).`as`(x)
                            .detachDelete(x)
                            .returning(Functions.count(Cypher.asterisk()))
                            .build()
                    )
            }

        }
    }
    return exposesWith
}
