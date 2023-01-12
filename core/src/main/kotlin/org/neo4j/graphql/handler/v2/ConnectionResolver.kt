package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.ListType
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.options.SortInput
import org.neo4j.graphql.schema.model.outputs.root_connection.RootNodeConnectionSelection
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler

/**
 * This class handles all the logic related to the querying of nodes.
 * This includes the augmentation of the query-fields and the related cypher generation
 */
class ConnectionResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx) {

        override fun augmentNode(node: Node): List<AugmentedField> {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.READ)) {
                return emptyList()
            }
            val nodeConnectionType = RootNodeConnectionSelection.Augmentation.generateNodeConnectionOT(node, ctx)
            val coordinates =
                addQueryField(node.rootTypeFieldNames.connection, nodeConnectionType.asRequiredType()) { args ->
                    WhereInput.NodeWhereInput.Augmentation
                        .generateWhereIT(node, ctx)
                        ?.let { args += inputValue(Constants.WHERE, it.asType()) }

                    SortInput.Companion.Augmentation
                        .generateSortIT(node, ctx)
                        ?.let { args += inputValue(Constants.SORT, ListType(it.asType())) }

                    args += inputValue(Constants.FIRST, Constants.Types.Int)
                    args += inputValue(Constants.AFTER, Constants.Types.String)
                }
            return AugmentedField(coordinates, ConnectionResolver(ctx.schemaConfig, node)).wrapList()
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        TODO()
    }
}
