package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.InputValueDefinition
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesDelete
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.delete.DeleteInput
import org.neo4j.graphql.translate.AuthTranslator
import org.neo4j.graphql.translate.TopLevelMatchTranslator
import org.neo4j.graphql.translate.createDeleteAndParams
import org.neo4j.graphql.utils.ResolveTree

class DeleteResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx) {

        override fun augmentNode(node: Node): List<AugmentedField> {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.DELETE)) {
                return emptyList()
            }

            val arguments = DeleteInputArguments.Augmentation.getFieldArguments(node, ctx)
            if (arguments.isEmpty()) {
                return emptyList()
            }

            val coordinates = addMutationField(
                node.rootTypeFieldNames.delete,
                Constants.Types.DeleteInfo.makeRequired(),
                arguments
            )

            return AugmentedField(coordinates, DeleteResolver(ctx.schemaConfig, node)).wrapList()
        }
    }

    private class DeleteInputArguments(node: Node, args: Dict) {
        val delete = args.nestedDict(Constants.DELETE_FIELD)
            ?.let { DeleteInput.NodeDeleteInput(node, it) }

        val where = args.nestedDict(Constants.WHERE)
            ?.let { WhereInput.NodeWhereInput(node, it) }

        object Augmentation : AugmentationBase {

            fun getFieldArguments(node: Node, ctx: AugmentationContext): List<InputValueDefinition> {
                val args = mutableListOf<InputValueDefinition>()

                DeleteInput.NodeDeleteInput.Augmentation
                    .generateContainerDeleteInputIT(node, ctx)?.let {
                        args += inputValue(Constants.DELETE_FIELD, it.asType())
                    }

                WhereInput.NodeWhereInput.Augmentation
                    .generateWhereIT(node, ctx)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }

                return args
            }
        }
    }


    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()
        val resolveTree = ResolveTree.resolve(env)
        val input = DeleteInputArguments(node, resolveTree.args)

        val dslNode = node.asCypherNode(queryContext, variable)

        var ongoingReading: StatementBuilder.ExposesWith =
            TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
                .translateTopLevelMatch(node, dslNode, null, input.where, AuthDirective.AuthOperation.DELETE)

        val withVars = listOf(dslNode.requiredSymbolicName)

        input.delete?.let {
            ongoingReading = createDeleteAndParams(
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
