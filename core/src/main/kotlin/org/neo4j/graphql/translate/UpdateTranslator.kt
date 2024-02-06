package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.directives.AuthorizationDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.create.RelationFieldInput
import org.neo4j.graphql.schema.model.inputs.update.UpdateFieldInput
import org.neo4j.graphql.schema.model.inputs.update.UpdateInput
import org.neo4j.graphql.translate.where.PrefixUsage
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

        var (condition, subqueries) = AuthorizationFactory.getAuthConditions(
            node,
            varName,
            updateInput.properties?.keys,
            schemaConfig,
            queryContext,
            AuthorizationDirective.AuthorizationOperation.UPDATE
        )

        val setProps = createSetPropertiesOnUpdate(
            varName,
            updateInput.properties,
            node,
            queryContext
        )
        setProps?.preConditions?.let { condition = condition and it }


        val postAuthCondition = AuthorizationFactory.getPostAuthConditions(
            node,
            varName,
            updateInput.properties?.keys,
            schemaConfig,
            queryContext,
            AuthorizationDirective.AuthorizationOperation.UPDATE
        )

        val prefix = chainStr ?: ChainString(schemaConfig, parentVar, "update")
        var result = ongoingReading

        condition?.let {
            result = result
                .maybeWith(withVars)
                .with(withVars) // TODO use maybeWith?
                .optionalWhere(it)
        }

        result = setProps
            ?.let {
                // TODO use createSetProperties and ?.let { .requiresExposeSet(withVars).set(*it.expressions.toTypedArray()) } ?: result
                //  instead of:
                var x = result
                for (exp in it.expressions) {
                    x = x.requiresExposeSet(withVars).set(exp)
                }
                x
            }
            ?: result


        updateInput.relations.forEach { (field, value) ->
            result = handleRelationField(field, value, prefix.extend(field), result)

            // TODO subscriptions
        }

        postAuthCondition.predicate?.apocValidatePredicate()?.let {
            result = result.with(withVars).where(it)
        }

        if (includeRelationshipValidation) {
            RelationshipValidationTranslator
                .createRelationshipValidations(node, varName, queryContext, schemaConfig)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    result = result
//                        .maybeWith(withVars)
                        .with(withVars) // TODO use maybe with?
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
                val _varName = ChainString(schemaConfig, varName, key)
                    .appendOnPrevious(index.takeIf { !field.isUnion })
                    .extend(refNode.name.takeIf { field.isUnion })
                    .appendOnPrevious(index.takeIf { field.isUnion })

                if (withVars.isNotEmpty()) {
                    result = result.with(withVars)
                }

                // input.update
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
                        parameterPrefix.extend(key, refNode.takeIf { field.isUnion })
                            .appendOnPrevious(index.takeIf { field.typeMeta.type.isList() })
                            .extend("disconnect"),
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
                        varName,
                        field,
                        refNode,
                        withVars,
                        result,
                        queryContext,
                        schemaConfig,
                        ChainString(schemaConfig, varName, field,
                            refNode.takeIf { field.isUnion }
                        ).appendOnPrevious(index).extend("connectOrCreate")
                    )
                }

                input.delete?.let { delete ->
                    result = createDeleteAndParams(
                        field,
                        refNode,
                        delete,
                        _varName.extend("delete"),
                        parentVar,
                        withVars,
                        parameterPrefix.extend(key)
                            .appendOnPrevious(index.takeIf { field.typeMeta.type.isList() })
                            .extend("delete"),
                        schemaConfig,
                        queryContext,
                        result
                    )
                }

                input.create
                    ?.let {
                        when (it) {
                            is RelationFieldInput.NodeCreateCreateFieldInputs -> it
                            is RelationFieldInput.InterfaceCreateFieldInputs -> it
                            is RelationFieldInput.UnionFieldInput -> it.getDataForNode(refNode)
                        }
                    }
                    ?.let { creates ->

                        if (withVars.isNotEmpty()) {
                            result = result.with(withVars)
                        }

                        creates.forEachIndexed { i, create ->

                            val baseName = ChainString(schemaConfig, _varName, "create").appendOnPrevious(i)
                            val nodeName = baseName.extend("node")
                            val propertiesName = baseName.extend("relationship")

                            val createNodeInput = when (create) {
                                is RelationFieldInput.InterfaceCreateFieldInput -> create.node?.getDataForNode(refNode)
                                is RelationFieldInput.NodeCreateCreateFieldInput -> create.node
                            }

                            val endNode = refNode.asCypherNode(queryContext, nodeName)
                            val relationVarName = propertiesName.takeIf { create.edge != null /*TODO or subscription*/ }
                            val relation = field.createDslRelation(parentVar, endNode, relationVarName)

                            val setProperties = createSetPropertiesOnCreate(
                                relation,
                                create.edge,
                                field.properties,
                                queryContext
                            )

                            result = CreateTranslator(schemaConfig, queryContext)
                                .createCreateAndParams(
                                    refNode,
                                    endNode,
                                    createNodeInput,
                                    withVars,
                                    createFactory = { dslNode -> result.maybeWith(withVars).create(dslNode) }
                                )
                                .merge(relation)
                                .let {
                                    if (setProperties.isNullOrEmpty()) {
                                        it
                                    } else {
                                        it.set(setProperties)
                                    }
                                }

                            // TODO subscriptions

                            RelationshipValidationTranslator
                                .createRelationshipValidations(refNode, endNode, queryContext, schemaConfig)
                                .let {
                                    result
                                        .maybeWith(withVars)
                                        .withSubQueries(it)
                                }
                        }
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
            ChainString(schemaConfig, varName, field.relationType.lowercase())
                .appendOnPrevious(index)
                .extend("relationship")

        val endNode = refNode.asCypherNode(queryContext, _varName)

        val rel = field.createDslRelation(
            Cypher.anyNode(parentVar.requiredSymbolicName),
            endNode,
            relationshipVariable
        )

        var where = createConnectionWhere(
            input.where,
            refNode,
            endNode,
            field,
            rel,
            parameterPrefix
                .extend(field, refNode.takeIf { field.isUnion })
                .appendOnPrevious(index.takeIf { field.typeMeta.type.isList() })
                .extend("where")
                .extend(refNode.takeIf { !field.isUnion }),
            schemaConfig,
            queryContext,
            usePrefix = PrefixUsage.APPEND
        )

        where = where and AuthorizationFactory.getAuthConditions(
            refNode,
            endNode,
            null, // TODO fields
            schemaConfig,
            queryContext,
            AuthorizationDirective.AuthorizationOperation.UPDATE
        )

        var result = Cypher
            .with(withVars)
            .match(rel)
            .optionalWhere(where.requiredCondition)
            .withSubQueries(where.preComputedSubQueries) // TODO create test for where sub-queries, I think we must move them before the where
            .let { reading ->
                field.properties?.let { edgeProperties ->
                    update.edge?.let { edgeUpdate ->
                        createSetPropertiesOnUpdate(
                            rel,
                            edgeUpdate,
                            edgeProperties,
                            queryContext
                        )
                            ?.let {
                                if (it.preConditions != null) {
                                    reading.with(rel).where(it.preConditions).set(it.expressions)
                                } else {
                                    reading.set(it.expressions)
                                }
                            }
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
                param
                    .extend(refNode.name.takeIf { field.isUnion })
                    .appendOnPrevious(index),
                refNode,
                nestedWithVars,
                queryContext,
                param.extend(field, refNode.takeIf { field.isUnion })
                    .appendOnPrevious(index.takeIf { field.typeMeta.type.isList() })
                    .extend("update", "node"),
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

            // TODO subscription 232
        }

        return result
            .returning(
                Functions.count(Cypher.asterisk())
                    .`as`(ChainString(schemaConfig, "update", _varName).resolveName())
            )
            .build()
    }
}
