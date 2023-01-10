package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.directives.FullTextDirective
import org.neo4j.graphql.domain.inputs.fulltext.FulltextSort
import org.neo4j.graphql.domain.inputs.fulltext.FulltextWhere
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.schema.AugmentationHandlerV2

/**
 * This class handles all the logic related to the querying of nodes.
 * This includes the augmentation of the query-fields and the related cypher generation
 */
class FulltextResolver private constructor(
    schemaConfig: SchemaConfig,
    val node: Node,
    val index: FullTextDirective.FullTextIndex
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandlerV2(ctx) {

        override fun augmentNode(node: Node): List<AugmentedField> {
            if (!node.isOperationAllowed(ExcludeDirective.ExcludeOperation.READ)) {
                return emptyList()
            }
            return node.fulltextDirective
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
    }

    override fun generateCypher(variable: String, field: Field, env: DataFetchingEnvironment): Statement {
        TODO()
    }
}
