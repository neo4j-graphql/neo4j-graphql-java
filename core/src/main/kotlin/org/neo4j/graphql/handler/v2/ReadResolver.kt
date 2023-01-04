package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.filter.FulltextPerIndex
import org.neo4j.graphql.domain.inputs.options.OptionsInput
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationHandlerV2
import org.neo4j.graphql.translate.AuthTranslator
import org.neo4j.graphql.translate.ProjectionTranslator
import org.neo4j.graphql.translate.TopLevelMatchTranslator
import org.neo4j.graphql.utils.ResolveTree

/**
 * This class handles all the logic related to the querying of nodes.
 * This includes the augmentation of the query-fields and the related cypher generation
 */
class ReadResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): AugmentedField? {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.READ)) {
                return null
            }
            val nodeType = generateNodeOT(node) ?: return null
            val coordinates =
                addQueryField(node.rootTypeFieldNames.read, NonNullType(ListType(nodeType.asRequiredType()))) { args ->
                    generateWhereIT(node)?.let { args += inputValue(Constants.WHERE, it.asType()) }
                    generateOptionsIT(node).let { args += inputValue(Constants.OPTIONS, it.asType()) }
                    generateFulltextIT(node)?.let { args += inputValue(Constants.FULLTEXT, it.asType()) }
                }
            return AugmentedField(coordinates, ReadResolver(ctx.schemaConfig, node))
        }
    }

    private class InputArguments(node: Node, args: Map<String, *>) {
        val where = args[Constants.WHERE]?.let { WhereInput.NodeWhereInput(node, Dict(it)) }
        val options = OptionsInput.create(args[Constants.OPTIONS])
        val fulltext = args[Constants.FULLTEXT]?.let { FulltextPerIndex(Dict(it)) }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()

        val resolveTree = ResolveTree.resolve(env)
        val input = InputArguments(node, resolveTree.args)

        val dslNode = node.asCypherNode(queryContext, variable)

        val optionsInput = input.options.merge(node.queryOptions)

        val authPredicates =
            AuthTranslator(schemaConfig, queryContext, allow = AuthTranslator.AuthOptions(dslNode, node))
                .createAuth(node.auth, AuthDirective.AuthOperation.READ)
                ?.let { it.apocValidatePredicate(Constants.AUTH_FORBIDDEN_ERROR) }

        var ongoingReading = TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
            .translateTopLevelMatch(
                node,
                dslNode,
                input.fulltext,
                input.where,
                AuthDirective.AuthOperation.READ,
                authPredicates
            )


        val projection = ProjectionTranslator()
            .createProjectionAndParams(node, dslNode, resolveTree, null, schemaConfig, env.variables, queryContext)

        projection.authValidate
            ?.let {
                ongoingReading = ongoingReading
                    .with(dslNode)
                    .apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR)
            }

        val mapProjection = dslNode.project(projection.projection).`as`(dslNode.requiredSymbolicName)
        return ongoingReading
            .withSubQueries(projection.subQueries)
            .returning(mapProjection)
            .applySortingSkipAndLimit(dslNode, optionsInput, schemaConfig)
            .build()
    }
}