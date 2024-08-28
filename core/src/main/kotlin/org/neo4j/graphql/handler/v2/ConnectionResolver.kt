package org.neo4j.graphql.handler.v2

import graphql.language.Field
import graphql.language.InputValueDefinition
import graphql.language.ListType
import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.Statement
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Entity
import org.neo4j.graphql.domain.ImplementingType
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
    val implementingType: ImplementingType
) : BaseDataFetcher(schemaConfig) {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.EntityAugmentation {

        override fun augmentEntity(entity: Entity): List<AugmentedField> {
            if (entity !is ImplementingType || entity.annotations.query?.read == false) {
                return emptyList()
            }

            val nodeConnectionType =
                RootNodeConnectionSelection.Augmentation.generateImplementingTypeConnectionOT(entity, ctx)
            val coordinates = addQueryField(
                entity.namings.rootTypeFieldNames.connection,
                nodeConnectionType.asRequiredType(),
                InputArguments.Augmentation(entity, ctx)
            )
            return AugmentedField(coordinates, ConnectionResolver(ctx.schemaConfig, entity)).wrapList()
        }
    }

    private class InputArguments(node: Node, args: Dict) {

        class Augmentation(val implementingType: ImplementingType, val ctx: AugmentationContext) :
            ArgumentsAugmentation {
            override fun augmentArguments(args: MutableList<InputValueDefinition>) {

                WhereInput.Augmentation
                    .generateWhereIT(implementingType, ctx)
                    ?.let { args += inputValue(Constants.WHERE, it.asType()) }

                SortInput.Companion.Augmentation
                    .generateSortIT(implementingType, ctx)
                    ?.let { args += inputValue(Constants.SORT, ListType(it.asType())) }

                args += inputValue(Constants.FIRST, Constants.Types.Int)
                args += inputValue(Constants.AFTER, Constants.Types.String)

                if (implementingType is Node && implementingType.annotations.fulltext != null) {
                    FulltextInput.Augmentation.generateFulltextInput(implementingType, ctx)
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
