package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.dto.DeleteInput
import org.neo4j.graphql.domain.dto.DictWithOn
import org.neo4j.graphql.domain.dto.RelationFieldsInput
import org.neo4j.graphql.domain.dto.UnionInput
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.translate.where.CreateConnectionWhere

class DeleteTranslator {
    companion object {
        fun createDeleteAndParams(
            node: Node,
            deleteInput: RelationFieldsInput,
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
            deleteInput.getInputsPerField().forEach { (relField, refNodes, input) ->
                refNodes.forEach { refNode ->
                    val deletes = (if (relField.isUnion) UnionInput(input).getDataForNode(refNode) else input)
                        ?.let { if (it is List<*>) it else listOf(it) }
                        ?.map { DeleteInput(it) }
                        ?: emptyList()

                    deletes.forEachIndexed { index, delete ->
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

                        var condition = CreateConnectionWhere(schemaConfig, queryContext)
                            .createConnectionWhere(
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
                                )
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

                        if (delete.delete != null) {
                            val dictWithOn = DictWithOn(delete.delete)
                            val (onDeletes, fieldInput) = dictWithOn.getFields(refNode)
                            val nestedDelete = RelationFieldsInput(refNode, fieldInput)

                            exposesWith = createDeleteAndParams(
                                refNode,
                                nestedDelete,
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

                            onDeletes
                                .takeIf { relField.isInterface }
                                ?.forEachIndexed { onDeleteIndex, onDelete ->
                                    exposesWith = createDeleteAndParams(
                                        refNode,
                                        RelationFieldsInput(refNode, onDelete),
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

                        val nodeToDelete = Cypher.name(endNodeName.extend("to_delete").resolveName())

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
    }
}
