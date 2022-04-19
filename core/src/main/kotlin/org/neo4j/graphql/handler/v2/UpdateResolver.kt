package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.ExposesSubqueryCall
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder.ExposesWith
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationHandlerV2
import org.neo4j.graphql.translate.CreateProjection
import org.neo4j.graphql.translate.CreateUpdate
import org.neo4j.graphql.translate.TopLevelMatchTranslator
import org.neo4j.graphql.utils.ResolveTree

class UpdateResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): AugmentedField? {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.UPDATE)) {
                return null
            }

            val responseType = addResponseType(node, node.typeNames.updateResponse, Constants.Types.UpdateInfo)
            val coordinates = addMutationField(node.rootTypeFieldNames.update, responseType.asRequiredType()) { args ->
                generateWhereIT(node)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }

                generateContainerUpdateIT(node)
                    ?.let { args += inputValue(Constants.UPDATE_FIELD, it.asType()) }

                generateContainerConnectInputIT(node)
                    ?.let { args += inputValue(Constants.CONNECT_FIELD, it.asType()) }

                generateContainerDisconnectInputIT(node)
                    ?.let { args += inputValue(Constants.DISCONNECT_FIELD, it.asType()) }

                generateContainerRelationCreateInputIT(node)
                    ?.let { args += inputValue(Constants.CREATE_FIELD, it.asType()) }

                generateContainerDeleteInputIT(node)
                    ?.let { args += inputValue(Constants.DELETE_FIELD, it.asType()) }

                generateContainerConnectOrCreateInputIT(node)
                    ?.let { args += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.asType()) }
            }

            return AugmentedField(coordinates, UpdateResolver(ctx.schemaConfig, node))
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()
        val dslNode = node.asCypherNode(queryContext, variable)
        val resolveTree = ResolveTree.resolve(env) // todo move into parent class

        val updateInput = resolveTree.args[Constants.UPDATE_FIELD]
        val connectInput = resolveTree.args[Constants.CONNECT_FIELD]
        val disconnectInput = resolveTree.args[Constants.DISCONNECT_FIELD]
        val createInput = resolveTree.args[Constants.CREATE_FIELD]
        val deleteInput = resolveTree.args[Constants.DELETE_FIELD]
        val connectOrCreateInput = resolveTree.args[Constants.CONNECT_OR_CREATE_FIELD]

        val withVars = listOf(dslNode.requiredSymbolicName)


        var ongoingReading: ExposesWith = TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
            .translateTopLevelMatch(node, dslNode, env.arguments, AuthDirective.AuthOperation.UPDATE)

        val nodeProjection = resolveTree.getFieldOfType(node.typeNames.updateResponse, node.plural)

        if (updateInput != null) {
            ongoingReading = CreateUpdate(
                    dslNode,
                    updateInput,
                    dslNode,
                    chainStr = null,
                    node,
                    withVars,
                    queryContext,
                    schemaConfig.namingStrategy.resolveParameter(resolveTree.name),
                    true,
                    schemaConfig,
                    ongoingReading
                )
                .createUpdateAndParams()
        }


        // TODO continue here
//        AuthTranslator(schemaConfig, queryContext, allow = AuthTranslator.AuthOptions(dslNode, node))
//            .createAuth(node.auth, AuthDirective.AuthOperation.READ)
//            ?.let { ongoingReading = ongoingReading.call(it.apocValidate(Constants.AUTH_FORBIDDEN_ERROR)) }

        val projection = CreateProjection()
            .createProjectionAndParams(node, dslNode, env, null, schemaConfig, env.variables, queryContext)

        projection.authValidate
            ?.let {
                ongoingReading = ongoingReading
                    .with(dslNode)
                    .call(it.apocValidate(Constants.AUTH_FORBIDDEN_ERROR))
            }

        val mapProjection = dslNode.project(projection.projection).`as`(dslNode.requiredSymbolicName)
        return (ongoingReading as OngoingReading)
            .withSubQueries(projection.subQueries)
            .returning(mapProjection)
            .build()
    }

}

