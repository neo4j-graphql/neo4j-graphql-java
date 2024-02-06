package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.InputValueDefinition
import graphql.language.ListType
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.ArgumentsAugmentation
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.schema.model.inputs.Dict
import org.neo4j.graphql.schema.model.inputs.WhereInput
import org.neo4j.graphql.schema.model.inputs.filter.FulltextInput
import org.neo4j.graphql.schema.model.inputs.options.SortInput
import org.neo4j.graphql.schema.model.outputs.root_connection.RootNodeConnectionSelection

/**
 * This class handles all the logic related to the querying of nodes.
 * This includes the augmentation of the query-fields and the related cypher generation
 */
class ConnectionResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.NodeAugmentation {

        override fun augmentNode(node: Node): List<AugmentedField> {
            if (node.annotations.query?.read == false) {
                return emptyList()
            }
            val nodeConnectionType = RootNodeConnectionSelection.Augmentation.generateNodeConnectionOT(node, ctx)
            val coordinates = addQueryField(
                node.namings.rootTypeFieldNames.connection,
                nodeConnectionType.asRequiredType(),
                InputArguments.Augmentation(node, ctx)
            )
            return AugmentedField(coordinates, ConnectionResolver(ctx.schemaConfig, node)).wrapList()
        }
    }

    private class InputArguments(node: Node, args: Dict) {

        class Augmentation(val node: Node, val ctx: AugmentationContext) : ArgumentsAugmentation {
            override fun augmentArguments(args: MutableList<InputValueDefinition>) {

                WhereInput.NodeWhereInput.Augmentation
                    .generateWhereIT(node, ctx)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }

                SortInput.Companion.Augmentation
                    .generateSortIT(node, ctx)
                    ?.let { args += inputValue(Constants.SORT, ListType(it.asType())) }

                args += inputValue(Constants.FIRST, Constants.Types.Int)
                args += inputValue(Constants.AFTER, Constants.Types.String)

                if (node.annotations.fulltext != null) {
                    FulltextInput.Augmentation.generateFulltextInput(node, ctx)
                        ?.let {
                            args += inputValue(Constants.FULLTEXT, it.asType()) {
                                description("Query a full-text index. Allows for the aggregation of results, but does not return the query score. Use the root full-text query fields if you require the score.".asDescription())
                            }
                        }
                }
            }
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        TODO()
    }
}
