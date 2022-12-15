package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.ExposesReturning
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.cypherdsl.core.StatementBuilder
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.dto.CreateInput
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationHandlerV2
import org.neo4j.graphql.translate.CreateTranslator
import org.neo4j.graphql.translate.ProjectionTranslator

class CreateResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): AugmentedField? {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.CREATE)) {
                return null
            }

            val coordinates = generateContainerCreateInputIT(node)?.let { inputType ->
                val responseType = addResponseType(node, node.typeNames.createResponse, Constants.Types.CreateInfo)
                addMutationField(node.rootTypeFieldNames.create, responseType.asRequiredType()) { args ->
                    args += inputValue(Constants.INPUT_FIELD, NonNullType(ListType(inputType.asRequiredType())))
                }
            } ?: return null

            return AugmentedField(coordinates, CreateResolver(ctx.schemaConfig, node))
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val inputs = env.arguments[Constants.INPUT_FIELD]
            ?.wrapList()
            ?.map { CreateInput.NodeCreateInput.create(node, it) }
            ?: throw IllegalArgumentException("expected an input of type ${env.fieldDefinition.getArgument(Constants.INPUT_FIELD).type}")

        val queryContext = env.queryContext()

        var queries: StatementBuilder.OngoingReadingWithoutWhere? = null
        val nodes = mutableListOf<org.neo4j.cypherdsl.core.Node>()
        inputs.forEachIndexed { index, value ->
            val dslNode = node.asCypherNode(queryContext).named("this$index")

            val statement = (
                    CreateTranslator(schemaConfig, queryContext)
                        .createCreateAndParams(
                            node,
                            dslNode,
                            value,
                            listOf(dslNode.requiredSymbolicName)
                        ) as ExposesReturning
                    ).returning(dslNode)
                .build()

            queries = queries?.call(statement) ?: Cypher.call(statement)
            nodes += dslNode
        }
        if (queries == null) {
            throw IllegalArgumentException("nothing to create for ${env.fieldDefinition}")
        }

        val (node, result) = if (nodes.size > 1) {
            val node = Cypher.anyNode().named("this")
            node to queries!!
                .unwind(Cypher.listOf(*nodes.map { it.requiredSymbolicName }.toTypedArray()))
                .`as`(node.requiredSymbolicName)
        } else {
            nodes[0] to queries!!
        }

//        val (subQueries, projectionEntries, authValidate) = ProjectionTranslator()
//            .createProjectionAndParams()

        val type = env.typeAsContainer()

//        val targetFieldSelection = env.selectionSet.getFields(field.name).first().selectionSet
//        val (projectionEntries, subQueries) = projectFields(node, type, env)
        return result
//            .withSubQueries(subQueries)
//            .returning(node.project(projectionEntries).`as`(variable))
            .returning(variable)
            .build()

    }
}

