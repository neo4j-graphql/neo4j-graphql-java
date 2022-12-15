package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.StatementBuilder.*
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.*
import org.neo4j.graphql.Constants.AUTH_FORBIDDEN_ERROR
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.dto.ConnectOrCreateInput
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.domain.fields.ConnectionField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.handler.utils.ChainString

class CreateTranslator(
    val schemaConfig: SchemaConfig,
    val queryContext: QueryContext?,
) {

    fun createCreateAndParams(
        node: Node,
        dslNode: org.neo4j.cypherdsl.core.Node,
        input: Map<*, *>?,
        withVars: List<SymbolicName>,
        includeRelationshipValidation: Boolean = false,
        createFactory: (dslNode: org.neo4j.cypherdsl.core.Node) -> OngoingUpdate = { Cypher.create(it) },
    ): BuildableMatchAndUpdate {
        var create: ExposesSet = createFactory(dslNode)

        if (input != null) {
            CreateSetPropertiesTranslator
                .createSetProperties(dslNode, input, CreateSetPropertiesTranslator.Operation.CREATE, node, schemaConfig)
                .let { create = create.set(it) }
        }

        var exposeWith = create as ExposesWith
        var authConditions: Condition? = null
        input?.forEach { (fieldName, value) ->
            val field = node.getField(fieldName as String) ?: return@forEach
            exposeWith = reducer(node, field, value, dslNode, withVars, exposeWith)

            // TODO why only for primitive fields in JS
            val fieldAuth = field.auth
            if (field !is RelationField && field !is ConnectionField && fieldAuth != null) {
                AuthTranslator(schemaConfig, queryContext, bind = AuthTranslator.AuthOptions(dslNode, node))
                    .createAuth(fieldAuth, AuthDirective.AuthOperation.CREATE)
                    ?.let { authConditions = authConditions and it }
            }

        }

        if (node.auth != null) {
            AuthTranslator(schemaConfig, queryContext, bind = AuthTranslator.AuthOptions(dslNode, node))
                .createAuth(node.auth, AuthDirective.AuthOperation.CREATE)
                ?.let { authConditions = authConditions and it }
        }

        authConditions?.let {
            exposeWith = exposeWith
                .with(*withVars.toTypedArray())
                .apocValidate(it, AUTH_FORBIDDEN_ERROR)
        }

        if (includeRelationshipValidation) {
            CreateRelationshipValidationTranslator
                .createRelationshipValidations(node, dslNode, queryContext, schemaConfig)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    exposeWith = exposeWith
                        .with(withVars)
                        .withSubQueries(it)
                }
        }

        return exposeWith as BuildableMatchAndUpdate
    }


    fun reducer(
        node: Node,
        field: BaseField,
        value: Any?,
        dslNode: org.neo4j.cypherdsl.core.Node,
        withVars: List<SymbolicName>,
        exposeWith: ExposesWith
    ): ExposesWith {
        var resultExposeWith: ExposesWith = exposeWith
        val varName = dslNode.requiredSymbolicName.value
        val fieldName = field.fieldName
        val varNameKey = ChainString(schemaConfig, varName, fieldName)

        val field = node.getField(fieldName)
        if (field is RelationField) {
            val refNodes: List<Node> = if (field.isUnion) {
                @Suppress("UNCHECKED_CAST")
                (value as? Map<String, *>)?.keys
                    ?.mapNotNull { unionTypeName -> field.getNode(unionTypeName) }
                    ?: emptyList()
            } else if (field.interfaze != null) {
                field.interfaze.implementations
            } else {
                listOf(field.node ?: throw IllegalStateException("missing node for ${node.name}.$fieldName"))
            }

            /*
            {
                unionField: {
                    UnionTypeName1:  {
                        filedOfUnionType1: value
                    }
                    UnionTypeName2:  {
                        filedOfUnionType2: value
                    }
                }
                interfaceField: {
                    filedOfInterface: value
                }
             */
            refNodes.forEach { refNode ->
                val (v, unionTypeName) = when {
                    field.isUnion -> (value as Map<*, *>)[refNode.name] to field.fieldName
                    field.isInterface -> value to field.fieldName
                    else -> value to null
                }
                (v as? Map<*, *>)
                    ?.let { v[Constants.CREATE_FIELD] }
                    ?.let { createField ->
                        val creates = when (field.typeMeta.type.isList()) {
                            true -> createField as? List<*>
                                ?: throw IllegalArgumentException("expected $fieldName to be a list")

                            false -> listOf(createField)
                        }

                        creates.forEachIndexed { index, createAny ->
                            val create = createAny as? Map<*, *> ?: return@forEachIndexed
                            val nodeField = create[Constants.NODE_FIELD] as? Map<*, *>
                            val edgeField = create[Constants.EDGE_FIELD] as? Map<*, *>

                            val subInput = if (field.isInterface) {
                                nodeField?.get(refNode.name) as? Map<*, *> ?: return@forEachIndexed
                            } else {
                                nodeField
                            }

                            val baseName = varNameKey.extend(index, unionTypeName)
                            val refDslNode = refNode.asCypherNode(queryContext, baseName.extend("node"))

                            val propertiesName = baseName.extend("relationship")
                                .takeIf { field.properties != null }

                            val dslRelation = field.createDslRelation(dslNode, refDslNode, propertiesName)
                            resultExposeWith = createCreateAndParams(
                                refNode,
                                refDslNode,
                                subInput,
                                withVars + refDslNode.requiredSymbolicName,
                                includeRelationshipValidation = false,
                                createFactory = { resultExposeWith.with(withVars).create(it) }
                            )
                                .merge(dslRelation)
                                .let { merge ->
                                    if (field.properties != null) {
                                        val expressions = CreateSetPropertiesTranslator.createSetProperties(
                                            dslRelation,
                                            edgeField ?: emptyMap<Any, Any>(),
                                            CreateSetPropertiesTranslator.Operation.CREATE,
                                            field.properties,
                                            schemaConfig
                                        )
                                        merge.set(expressions)
                                    } else {
                                        merge
                                    }
                                }

                            CreateRelationshipValidationTranslator
                                .createRelationshipValidations(refNode, refDslNode, queryContext, schemaConfig)
                                .takeIf { it.isNotEmpty() }
                                ?.let {
                                    resultExposeWith = resultExposeWith
                                        .with(withVars + refDslNode.requiredSymbolicName)
                                        .withSubQueries(it)
                                }
                        }
                    }


                (v as? Map<*, *>)?.get(Constants.CONNECT_FIELD)?.let {
                    if (!field.isInterface) {
                        resultExposeWith = CreateConnectTranslator.createConnectAndParams(
                            schemaConfig,
                            queryContext,
                            node,
                            varNameKey.extend(
                                unionTypeName.takeIf { field.isUnion },
                                "connect"
                            ),
                            dslNode,
                            fromCreate = true,
                            withVars,
                            field,
                            it,
                            resultExposeWith,
                            listOf(refNode),
                            labelOverride = unionTypeName,
                        )
                    }
                }


                (v as? Map<*, *>)?.let { v[Constants.CONNECT_OR_CREATE_FIELD] }?.let { input ->
                    resultExposeWith = ConnectOrCreateTranslator.createConnectOrCreateAndParams(
                        (input as? List<*> ?: listOf(input)).map { ConnectOrCreateInput(it) },
                        varNameKey.extend(unionTypeName.takeIf { field.isUnion }, "connectOrCreate"),
                        dslNode,
                        field,
                        refNode,
                        withVars,
                        resultExposeWith,
                        schemaConfig,
                        queryContext
                    )
                }
            }

            (value as? Map<*, *>)
                ?.get(Constants.CONNECT_FIELD)
                ?.takeIf { field.isInterface }
                ?.let {
                    resultExposeWith = CreateConnectTranslator.createConnectAndParams(
                        schemaConfig,
                        queryContext,
                        node,
                        varNameKey.extend("connect"),
                        dslNode,
                        fromCreate = true,
                        withVars,
                        field,
                        it,
                        resultExposeWith,
                        refNodes,
                        labelOverride = null
                    )
                }
        }
        return resultExposeWith
    }

}
