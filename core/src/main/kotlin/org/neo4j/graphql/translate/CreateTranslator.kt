package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.StatementBuilder.*
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.*
import org.neo4j.graphql.Constants.AUTH_FORBIDDEN_ERROR
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.inputs.create.CreateFieldInput
import org.neo4j.graphql.domain.inputs.create.CreateInput
import org.neo4j.graphql.domain.inputs.create.RelationFieldInput
import org.neo4j.graphql.handler.utils.ChainString

class CreateTranslator(
    val schemaConfig: SchemaConfig,
    val queryContext: QueryContext?,
) {

    fun createCreateAndParams(
        node: Node,
        dslNode: org.neo4j.cypherdsl.core.Node,
        input: CreateInput?,
        withVars: List<SymbolicName>,
        includeRelationshipValidation: Boolean = false,
        createFactory: (dslNode: org.neo4j.cypherdsl.core.Node) -> OngoingUpdate = { Cypher.create(it) },
    ): BuildableMatchAndUpdate {
        var create: ExposesSet = createFactory(dslNode)

        var authConditions: Condition? = null
        input?.properties?.let { properties ->
            createSetProperties(dslNode, properties, Operation.CREATE, node, schemaConfig)
                ?.let { create = create.set(it) }

            properties.keys.forEach { field ->
                if (field.auth != null) {
                    AuthTranslator(schemaConfig, queryContext, bind = AuthTranslator.AuthOptions(dslNode, node))
                        .createAuth(field.auth, AuthDirective.AuthOperation.CREATE)
                        ?.let { authConditions = authConditions and it }
                }
            }
        }

        var exposeWith = create as ExposesWith
        input?.relations?.forEach { (field, value) ->
            exposeWith = handleRelation(node, field, value, dslNode, withVars, exposeWith)
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
            RelationshipValidationTranslator
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


    private fun handleRelation(
        node: Node,
        field: RelationField,
        value: CreateFieldInput,
        dslNode: org.neo4j.cypherdsl.core.Node,
        withVars: List<SymbolicName>,
        exposeWith: ExposesWith
    ): ExposesWith {
        var resultExposeWith: ExposesWith = exposeWith
        val varName = dslNode.requiredSymbolicName.value
        val fieldName = field.fieldName
        val varNameKey = ChainString(schemaConfig, varName, fieldName)

        val refNodes = if (value is CreateFieldInput.UnionFieldInput) {
            value.dataPerNode.keys
        } else if (field.interfaze != null) {
            field.interfaze.implementations.values
        } else {
            listOf(field.node ?: throw IllegalStateException("missing node for ${node.name}.$fieldName"))
        }

        refNodes.forEach { refNode ->
            val (v, unionTypeName) = when (value) {
                is CreateFieldInput.UnionFieldInput -> value.getDataForNode(refNode) to field.fieldName
                is CreateFieldInput.ImplementingTypeFieldInput -> value to field.fieldName.takeIf { field.isInterface }
            }
            v?.create?.let { creates ->
                creates.forEachIndexed { index, create ->
                    val edgeField = create.edge
                    val nestedInput = when (create) {
                        is RelationFieldInput.NodeCreateCreateFieldInput -> create.node
                        is RelationFieldInput.InterfaceCreateFieldInput -> create.node?.getDataForNode(refNode)
                    }

                    val baseName = varNameKey.extend(index, unionTypeName)
                    val refDslNode = refNode.asCypherNode(queryContext, baseName.extend("node"))

                    val propertiesName = baseName.extend("relationship")
                        .takeIf { field.properties != null }

                    val dslRelation = field.createDslRelation(dslNode, refDslNode, propertiesName)
                    resultExposeWith = createCreateAndParams(
                        refNode,
                        refDslNode,
                        nestedInput,
                        withVars + refDslNode.requiredSymbolicName,
                        includeRelationshipValidation = false,
                        createFactory = { resultExposeWith.with(withVars).create(it) }
                    )
                        .merge(dslRelation)
                        .let { merge ->
                            if (field.properties != null) {
                                createSetProperties(
                                    dslRelation,
                                    edgeField,
                                    Operation.CREATE,
                                    field.properties,
                                    schemaConfig
                                )?.let { merge.set(it) } ?: merge
                            } else {
                                merge
                            }
                        }

                    RelationshipValidationTranslator
                        .createRelationshipValidations(refNode, refDslNode, queryContext, schemaConfig)
                        .takeIf { it.isNotEmpty() }
                        ?.let {
                            resultExposeWith = resultExposeWith
                                .with(withVars + refDslNode.requiredSymbolicName)
                                .withSubQueries(it)
                        }
                }
            }

            v?.connect?.takeIf { !field.isInterface }?.let { connects ->
                resultExposeWith = ConnectTranslator(
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
                    connects,
                    resultExposeWith,
                    listOf(refNode),
                    labelOverride = unionTypeName,
                ).createConnectAndParams()
            }


            (v as? CreateFieldInput.NodeFieldInput)?.connectOrCreate?.let { connectOrCreate ->
                resultExposeWith = createConnectOrCreate(
                    connectOrCreate,
                    varNameKey.extend(unionTypeName, "connectOrCreate"),
                    dslNode,
                    field,
                    refNode,
                    withVars,
                    resultExposeWith,
                    queryContext,
                    schemaConfig
                )
            }
        }

        if (value is CreateFieldInput.InterfaceFieldInput) {
            resultExposeWith = ConnectTranslator(
                schemaConfig,
                queryContext,
                node,
                varNameKey.extend("connect"),
                dslNode,
                fromCreate = true,
                withVars,
                field,
                value.connect,
                resultExposeWith,
                refNodes,
                labelOverride = null
            )
                .createConnectAndParams()
        }
        return resultExposeWith
    }

}
