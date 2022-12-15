package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.dto.ConnectOrCreateInput
import org.neo4j.graphql.domain.dto.CreateInput
import org.neo4j.graphql.domain.dto.Dict
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.handler.utils.ChainString

class ConnectOrCreateTranslator {
    companion object {
        fun createConnectOrCreateAndParams(
            inputs: List<ConnectOrCreateInput>?,
            varName: ChainString,
            parentVar: Node,
            relationField: RelationField,
            refNode: org.neo4j.graphql.domain.Node,
            withVars: List<SymbolicName>,
            ongoingReading: ExposesWith,
            schemaConfig: SchemaConfig,
            queryContext: QueryContext?
        ): ExposesWith {
            var innerStatement: ExposesWith = Cypher.with(withVars);
            inputs?.forEachIndexed { index, input ->
                val subqueryBaseName = varName.extend(index)

                val targetNode = refNode.asCypherNode(queryContext, subqueryBaseName)
                val onCreate = input.onCreate?.let { CreateInput(it) }

                AuthTranslator(
                    schemaConfig, queryContext,
                    allow = AuthTranslator.AuthOptions(
                        targetNode,
                        refNode,
                        subqueryBaseName.extend(refNode, index, "allow")
                    )
                ).createAuth(refNode.auth, AuthDirective.AuthOperation.CONNECT, AuthDirective.AuthOperation.CREATE)
                    ?.let {
                        innerStatement = innerStatement
                            .maybeWith(withVars)
                            .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
                    }


                val whereNodeParameters = input.where?.let { Dict(it) }?.get(Constants.NODE_FIELD)?.let { Dict(it) }
                var node = targetNode
                // TODO should we use where instead?
                CreateSetPropertiesTranslator.createSetProperties(
                    targetNode,
                    whereNodeParameters,
                    CreateSetPropertiesTranslator.Operation.UPDATE,
                    refNode,
                    schemaConfig,
                    subqueryBaseName.extend("node")
                )
                    .takeIf { it.isNotEmpty() }
                    ?.let { node = node.withProperties(*it.toTypedArray()) }

                innerStatement = innerStatement
                    .maybeWith(withVars)
                    .merge(node)
                    .let { merge ->
                        CreateSetPropertiesTranslator.createSetProperties(
                            targetNode,
                            onCreate?.node,
                            CreateSetPropertiesTranslator.Operation.CREATE,
                            refNode,
                            schemaConfig,
                            subqueryBaseName.extend("on_create")
                        )
                            .takeIf { it.isNotEmpty() }
                            ?.let { merge.onCreate().set(it) }
                            ?: merge
                    }

                val rel =
                    relationField.createDslRelation(parentVar, targetNode, subqueryBaseName.extend("relationship"))
                innerStatement = (innerStatement as ExposesMerge).merge(rel)
                    .let { merge ->
                        relationField.properties?.let { properties ->
                            CreateSetPropertiesTranslator.createSetProperties(
                                rel,
                                onCreate?.edge,
                                CreateSetPropertiesTranslator.Operation.CREATE,
                                properties,
                                schemaConfig,
                                subqueryBaseName.extend("on_create")
                            ).takeIf { it.isNotEmpty() }
                        }
                            ?.let { merge.onCreate().set(it) }
                            ?: merge
                    }

            }

            return ongoingReading
                .with(withVars)
                .call(
                    innerStatement
                        .maybeWith(withVars)
                        .returning(Functions.count(Cypher.asterisk()))
                        .build()
                )
        }
    }
}
