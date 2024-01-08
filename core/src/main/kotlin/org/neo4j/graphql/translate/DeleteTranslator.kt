package org.neo4j.graphql.translate

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.ExposesWith
import org.neo4j.cypherdsl.core.Functions
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.model.inputs.delete.DeleteFieldInput
import org.neo4j.graphql.schema.model.inputs.delete.DeleteInput
import org.neo4j.graphql.translate.where.PrefixUsage
import org.neo4j.graphql.translate.where.createConnectionWhere

fun createDeleteAndParams(
    relField: RelationField,
    refNode: Node,
    input: DeleteFieldInput,
    varName: ChainString,
    parentVar: org.neo4j.cypherdsl.core.Node,
    withVars: List<SymbolicName>,
    parameterPrefix: ChainString,
    schemaConfig: SchemaConfig,
    queryContext: QueryContext,
    ongoingReading: ExposesWith
): ExposesWith {
    var exposesWith = ongoingReading
    val deletes = when (input) {
        is DeleteFieldInput.NodeDeleteFieldInputs -> input
        is DeleteFieldInput.InterfaceDeleteFieldInputs -> input
        is DeleteFieldInput.UnionDeleteFieldInput -> input.getDataForNode(refNode)
    }

    deletes?.forEachIndexed { index, delete ->
        val endNodeName = varName
            .extend(refNode.takeIf { (relField.isUnion || relField.isInterface) })
            .appendOnPrevious(index)

        val endNode = refNode.asCypherNode(queryContext, endNodeName)
        val dslRelation = relField.createDslRelation(
            parentVar, endNode,
            endNodeName.extend("relationship"),
            startLeft = true
        )

        var (condition, whereSubquery) = createConnectionWhere(
            delete.where,
            refNode,
            endNode,
            relField,
            dslRelation,
            parameterPrefix
                .extend(refNode.takeIf { relField.isUnion })
                .appendOnPrevious(index.takeIf { relField.typeMeta.type.isList() })
                .extend("where", refNode),
            schemaConfig,
            queryContext,
            usePrefix = PrefixUsage.APPEND
        )

        if (whereSubquery.isNotEmpty()) {
            TODO()
        }

        AuthTranslator(schemaConfig, queryContext, where = AuthTranslator.AuthOptions(endNode, refNode))
            .createAuth(refNode.auth, AuthDirective.AuthOperation.DELETE)
            ?.let { condition = condition and it }

        exposesWith = exposesWith.with(withVars)
            .optionalMatch(dslRelation)
            .optionalWhere(condition)
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
                inputExcludingOnForNode,
                endNodeName,
                endNode,
                withVars + endNode.requiredSymbolicName,
                parameterPrefix.extend(refNode.takeIf { relField.isUnion })
                    .appendOnPrevious(index.takeIf { relField.typeMeta.type.isList() })
                    .extend("delete"),
                schemaConfig,
                queryContext,
                exposesWith
            )

            if (relField.isInterface) {
                TODO()
            }
            inputOnForNode?.forEachIndexed { onDeleteIndex, onDelete ->
                exposesWith = createDeleteAndParams(
                    onDelete,
                    endNodeName,
                    endNode,
                    withVars + endNode.requiredSymbolicName,
                    parameterPrefix.extend(
                        index.takeIf { relField.typeMeta.type.isList() },
                        "delete",
                        "_on",
                        refNode,
                        onDeleteIndex
                    ),
                    schemaConfig,
                    queryContext,
                    exposesWith
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
                    .returning(Functions.count(Cypher.asterisk()).`as`("_"))
                    .build()
            )
    }
    return exposesWith
}

fun createDeleteAndParams(
    deleteInput: DeleteInput,
    varName: ChainString,
    parentVar: org.neo4j.cypherdsl.core.Node,
    withVars: List<SymbolicName>,
    parameterPrefix: ChainString,
    schemaConfig: SchemaConfig,
    queryContext: QueryContext,
    ongoingReading: ExposesWith,
): ExposesWith {

    var exposesWith: ExposesWith = ongoingReading
    deleteInput.relations.forEach { (relField, input) ->
        relField.getReferenceNodes().forEach { refNode ->
            exposesWith = createDeleteAndParams(
                relField,
                refNode,
                input,
                varName.extend(relField),
                parentVar,
                withVars,
                parameterPrefix.extend(relField),
                schemaConfig,
                queryContext,
                exposesWith
            )

        }
    }
    return exposesWith
}
