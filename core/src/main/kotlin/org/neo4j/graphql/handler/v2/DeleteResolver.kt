package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.InputValueDefinition
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.ExposesWith
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesDelete
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.AuthorizationDirective
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.schema.ArgumentsAugmentation
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

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.NodeAugmentation {

        override fun augmentNode(node: Node): List<AugmentedField> {
            if (node.annotations.mutation?.delete == false) {
                return emptyList()
            }
            val coordinates = addMutationField(
                node.namings.rootTypeFieldNames.delete,
                Constants.Types.DeleteInfo.makeRequired(),
                DeleteInputArguments.Augmentation(node, ctx)
            ) ?: return emptyList()

            return AugmentedField(coordinates, DeleteResolver(ctx.schemaConfig, node)).wrapList()
        }
    }

    private class DeleteInputArguments(node: Node, args: Dict) {
        val delete = args.nestedDict(Constants.DELETE_FIELD)
            ?.let { DeleteInput.NodeDeleteInput(node, it) }

        val where = args.nestedDict(Constants.WHERE)
            ?.let { WhereInput.NodeWhereInput(node, it) }

        class Augmentation(val node: Node, val ctx: AugmentationContext) : ArgumentsAugmentation {

            override fun augmentArguments(args: MutableList<InputValueDefinition>) {
                DeleteInput.NodeDeleteInput.Augmentation
                    .generateContainerDeleteInputIT(node, ctx)?.let {
                        args += inputValue(Constants.DELETE_FIELD, it.asType())
                    }

                WhereInput.NodeWhereInput.Augmentation
                    .generateWhereIT(node, ctx)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }
            }
        }
    }


    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()
        val resolveTree = ResolveTree.resolve(env)
        val input = DeleteInputArguments(node, resolveTree.args)

        val dslNode = node.asCypherNode(queryContext, variable)

        var ongoingReading: ExposesWith =
            TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
                .translateTopLevelMatch(
                    node,
                    dslNode,
                    null,
                    input.where,
                    AuthorizationDirective.AuthorizationOperation.DELETE
                )

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

