package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesDelete
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.delete.DeleteInput
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.AugmentationHandlerV2
import org.neo4j.graphql.translate.AuthTranslator
import org.neo4j.graphql.translate.TopLevelMatchTranslator
import org.neo4j.graphql.translate.createDeleteAndParams
import org.neo4j.graphql.utils.ResolveTree

class DeleteResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): List<AugmentedField> {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.DELETE)) {
                return emptyList()
            }

            val coordinates =
                addMutationField(node.rootTypeFieldNames.delete, Constants.Types.DeleteInfo.makeRequired()) { args ->
                    generateWhereIT(node)?.let { args += inputValue(Constants.WHERE, it.asType()) }
                    generateContainerDeleteInputIT(node)?.let {
                        args += inputValue(Constants.DELETE_FIELD, it.asType())
                    }
                }

            return AugmentedField(coordinates, DeleteResolver(ctx.schemaConfig, node)).wrapList()
        }
    }

    private class InputArguments(node: Node, args: Map<String, *>) {
        val delete = args[Constants.DELETE_FIELD]?.let { DeleteInput.NodeDeleteInput(node, Dict(it)) }
        val where = args[Constants.WHERE]?.let { WhereInput.NodeWhereInput(node, Dict(it)) }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()
        val resolveTree = ResolveTree.resolve(env)
        val input = InputArguments(node, resolveTree.args)

        val dslNode = node.asCypherNode(queryContext, variable)

        var ongoingReading: StatementBuilder.ExposesWith =
            TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
                .translateTopLevelMatch(node, dslNode, null, input.where, AuthDirective.AuthOperation.DELETE)

        val withVars = listOf(dslNode.requiredSymbolicName)

        input.delete?.let {
            ongoingReading = createDeleteAndParams(
                node,
                it,
                ChainString(schemaConfig, variable),
                dslNode,
                withVars,
                ChainString(schemaConfig, variable, resolveTree.name, "args", "delete"),
                schemaConfig,
                queryContext,
                ongoingReading
            )
        }

        AuthTranslator(schemaConfig, queryContext, allow = AuthTranslator.AuthOptions(dslNode, node))
            .createAuth(node.auth, AuthDirective.AuthOperation.DELETE)
            ?.let {
                ongoingReading = ongoingReading
                    .with(dslNode)
                    .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
            }


        return (ongoingReading as ExposesDelete)
            .detachDelete(dslNode)
            // TODO subscription
            .build()
    }
}

