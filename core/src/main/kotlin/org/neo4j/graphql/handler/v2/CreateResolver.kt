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
import org.neo4j.graphql.schema.model.inputs.create.CreateInput
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.translate.CreateTranslator

class CreateResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx) {

        override fun augmentNode(node: Node): List<AugmentedField> {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.CREATE)) {
                return emptyList()
            }

            val coordinates = CreateInput.Augmentation
                .generateContainerCreateInputIT(node, ctx)
                ?.let { inputType ->

                    val responseType = addResponseType(node, node.typeNames.createResponse, Constants.Types.CreateInfo)
                    addMutationField(node.rootTypeFieldNames.create, responseType.asRequiredType()) { args ->
                        args += inputValue(Constants.INPUT_FIELD, NonNullType(ListType(inputType.asRequiredType())))
                    }

                } ?: return emptyList()

            return AugmentedField(coordinates, CreateResolver(ctx.schemaConfig, node)).wrapList()
        }
    }

    private class InputArguments(node: Node, args: Map<String, *>) {
        val input = args[Constants.INPUT_FIELD]
            ?.wrapList()
            ?.map { CreateInput.create(node, it) }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        val arguments = InputArguments(node, env.arguments)

        val queryContext = env.queryContext()

        var queries: StatementBuilder.OngoingReadingWithoutWhere? = null
        val nodes = mutableListOf<org.neo4j.cypherdsl.core.Node>()
        arguments.input?.forEachIndexed { index, value ->
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
//            .returning(1.asCypherLiteral()) //TODO
//            .returning(node.project(projectionEntries).`as`(variable))
            .returning(variable)
            .build()

    }
}

