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
import org.neo4j.graphql.domain.dto.OptionsInput
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationHandlerV2
import org.neo4j.graphql.translate.AuthTranslator
import org.neo4j.graphql.translate.CreateProjection
import org.neo4j.graphql.translate.TopLevelMatchTranslator

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

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val queryContext = env.queryContext()
        val dslNode = node.asCypherNode(queryContext, variable)

        val optionsInput = OptionsInput
            .create(env.arguments[Constants.OPTIONS])
            .merge(node.queryOptions)

        var ongoingReading = TopLevelMatchTranslator(schemaConfig, env.variables, queryContext)
            .translateTopLevelMatch(node, dslNode, env.arguments, AuthDirective.AuthOperation.READ)

        AuthTranslator(schemaConfig, queryContext, allow = AuthTranslator.AuthOptions(dslNode, node))
            .createAuth(node.auth, AuthDirective.AuthOperation.READ)
            ?.let { ongoingReading = ongoingReading.apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR) }

        val projection = CreateProjection()
            .createProjectionAndParams(node, dslNode, env, null, schemaConfig, env.variables, queryContext)

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
