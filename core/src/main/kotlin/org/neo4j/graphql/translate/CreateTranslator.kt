package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Condition
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.StatementBuilder.*
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.*
import org.neo4j.graphql.Constants.AUTH_FORBIDDEN_ERROR
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.dto.CreateInput
import org.neo4j.graphql.domain.dto.CreateRelationFieldInput
import org.neo4j.graphql.domain.fields.RelationField

class CreateTranslator(
    val schemaConfig: SchemaConfig,
    val queryContext: QueryContext?,
) {

    fun createCreateAndParams(
        node: Node,
        dslNode: org.neo4j.cypherdsl.core.Node,
        input: CreateInput.NodeCreateInput?,
        withVars: List<SymbolicName>,
        includeRelationshipValidation: Boolean = false,
        createFactory: (dslNode: org.neo4j.cypherdsl.core.Node) -> OngoingUpdate = { Cypher.create(it) },
    ): BuildableMatchAndUpdate {
        var create: ExposesSet = createFactory(dslNode)

        var authConditions: Condition? = null
        input?.properties?.let { properties ->
            createSetProperties(dslNode, properties, Operation.CREATE, node, schemaConfig)
                .let { create = create.set(it) }

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
                .call(it.apocValidate(AUTH_FORBIDDEN_ERROR))
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
        value: CreateRelationFieldInput,
        dslNode: org.neo4j.cypherdsl.core.Node,
        withVars: List<SymbolicName>,
        exposeWith: ExposesWith
    ): ExposesWith {
        var resultExposeWith: ExposesWith = exposeWith
        val varName = dslNode.requiredSymbolicName.value
        val fieldName = field.fieldName
        val varNameKey = schemaConfig.namingStrategy.resolveName(varName, fieldName)

        val refNodes = if (value is CreateRelationFieldInput.UnionCreateRelationFieldInput) {
            value.keys
        } else if (field.interfaze != null) {
            field.interfaze.implementations.values
        } else {
            listOf(field.node ?: throw IllegalStateException("missing node for ${node.name}.$fieldName"))
        }

        refNodes.forEach { refNode ->
            val (v, unionTypeName) = when (value) {
                is CreateRelationFieldInput.UnionCreateRelationFieldInput -> value[refNode] to field.fieldName
                is CreateRelationFieldInput.NodeCreateRelationFieldInput -> value to field.fieldName.takeIf { field.isInterface }
                else -> throw IllegalStateException("unknown type for CreateFieldInput")
            }
            v?.create?.let { creates ->
                creates.forEachIndexed { index, create ->
                    val edgeField = create.edge
                    val nestedInput = when (val nodeField = create.node) {
                        is CreateInput.NodeCreateInput -> nodeField
                        is CreateInput.InterfaceCreateInput -> nodeField[refNode]
                        else -> null
                    }

                    fun getName(suffix: String) = schemaConfig.namingStrategy.resolveName(
                        varName,
                        fieldName + index,
                        unionTypeName,
                        suffix
                    )

                    val propertiesName = field.properties?.let { getName("relationship") }

                    val nodeName = getName("node")
                    val refDslNode = refNode.asCypherNode(queryContext).named(nodeName)

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
                            if (propertiesName != null) {
                                val expressions = createSetProperties(
                                    dslRelation,
                                    edgeField,
                                    Operation.CREATE,
                                    field.properties,
                                    schemaConfig
                                )
                                merge.set(expressions)
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
                    schemaConfig.namingStrategy.resolveName(
                        varNameKey,
                        if (field.isUnion) unionTypeName else null,
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


            v?.connectOrCreate?.let { connectOrCreate ->
                resultExposeWith =  createConnectOrCreate(
                    connectOrCreate,
                    schemaConfig.namingStrategy.resolveName(varNameKey, unionTypeName, "connectOrCreate"),
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

        if (value is CreateRelationFieldInput.NodeCreateRelationFieldInput && field.isInterface) {
            resultExposeWith = ConnectTranslator(
                schemaConfig,
                queryContext,
                node,
                schemaConfig.namingStrategy.resolveName(varNameKey, "connect"),
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
