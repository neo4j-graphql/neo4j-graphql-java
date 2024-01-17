package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.FulltextDirective
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.schema.model.inputs.fulltext.FulltextSort
import org.neo4j.graphql.schema.model.inputs.fulltext.FulltextWhere

/**
 * This class handles all the logic related to the querying of nodes.
 * This includes the augmentation of the query-fields and the related cypher generation
 */
class FulltextResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node,
    val index: FulltextDirective.FullTextIndex
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.NodeAugmentation {

        override fun augmentNode(node: Node): List<AugmentedField> {
            if (node.annotations.query?.read == false) {
                return emptyList()
            }
            return node.annotations.fulltext
                ?.indexes
                ?.map { fullTextIndex ->
                    val name =
                        fullTextIndex.queryName ?: "${node.plural}Fulltext${fullTextIndex.indexName.capitalize()}"
                    val fulltextType = generateNodeFulltextOT(node) ?: return emptyList()

                    val coordinates =
                        addQueryField(name, NonNullType(ListType(fulltextType.asRequiredType()))) { args ->
                            args += inputValue(Constants.LIMIT, Constants.Types.Int)
                            args += inputValue(Constants.OFFSET, Constants.Types.Int)
                            args += inputValue(Constants.FULLTEXT_PHRASE, Constants.Types.String.makeRequired())
                            args += inputValue(
                                Constants.SORT,
                                ListType(FulltextSort.Augmentation.generateFulltextSort(node, ctx).asType(true))
                            )
                            args += inputValue(
                                Constants.WHERE,
                                FulltextWhere.Augmentation.generateFulltextWhere(node, ctx).asType()
                            )
                        }
                    AugmentedField(coordinates, FulltextResolver(ctx.schemaConfig, node, fullTextIndex))
                }
                ?: emptyList()
        }

        //TODO remove?  new Handling?
        private fun generateNodeFulltextOT(node: Node) = ctx.getOrCreateObjectType("${node.name}FulltextResult", {
            description("The result of a fulltext search on an index of ${node.name}".asDescription())
        }) { fields, _ ->
            fields += field(node.name.lowercase(), node.name.asType(true))
            fields += field(Constants.SCORE, Constants.Types.Float.makeRequired())
        }
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        TODO()
    }
}
