package org.neo4j.graphql.handler

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.FieldCoordinates
import org.neo4j.graphql.Constants
import org.neo4j.graphql.domain.Entity
import org.neo4j.graphql.domain.ImplementingType
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.schema.model.outputs.NodeConnectionEdgeFieldSelection
import org.neo4j.graphql.schema.model.outputs.NodeConnectionFieldSelection
import org.neo4j.graphql.schema.model.outputs.PageInfoSelection
import org.neo4j.graphql.utils.PagingUtils
import org.neo4j.graphql.utils.ResolveTree

internal class ImplementingTypeConnectionFieldResolver(
    private val relationBaseField: RelationBaseField
) : DataFetcher<Any> {

    class Factory(ctx: AugmentationContext) : AugmentationHandler(ctx), AugmentationHandler.EntityAugmentation {

        override fun augmentEntity(entity: Entity): List<AugmentedField> {
            if (entity !is ImplementingType) {
                return emptyList()
            }
            return entity.relationBaseFields.map {
                AugmentedField(
                    FieldCoordinates.coordinates(entity.name, it.namings.connectionFieldName),
                    ImplementingTypeConnectionFieldResolver(it)
                )
            }
        }
    }

    override fun get(env: DataFetchingEnvironment): Any {
        val resolveTree = ResolveTree.resolve(env)

        val source = env.getSource<Map<String, *>>() ?: error("No source")
        val connection = (source[resolveTree.aliasOrName] as? Map<*, *>)
            ?.toMutableMap()
            ?: error("No connection field ${resolveTree.aliasOrName} in source $source")

        val selection = NodeConnectionFieldSelection.parse(relationBaseField.connectionField, resolveTree)

        val first = env.getArgument<Int>(Constants.FIRST)
        val after = env.getArgument<String>(Constants.AFTER)

        val edges = connection[Constants.EDGES_FIELD] as List<*>
        val totalCount = connection[Constants.TOTAL_COUNT] as Long

        val lastEdgeCursor = PagingUtils.getOffsetFromCursor(after) ?: -1
        val sliceStart = lastEdgeCursor + 1
        val sliceEnd = sliceStart + (first ?: edges.size)

        return selection.project {
            field(NodeConnectionFieldSelection::edges) {
                edges
                    .filterIsInstance<Map<*, *>>()
                    .mapIndexed { index, value ->
                        project {
                            lazyField(NodeConnectionEdgeFieldSelection::cursor) { PagingUtils.createCursor(sliceStart + index) }
                            allOf(value)
                        }
                    }
            }
            field(NodeConnectionFieldSelection::totalCount, totalCount)
            field(NodeConnectionFieldSelection::pageInfo) {
                project {
                    lazyField(PageInfoSelection::startCursor) {
                        if (edges.isEmpty()) null else PagingUtils.createCursor(sliceStart)
                    }
                    lazyField(PageInfoSelection::endCursor) {
                        if (edges.isEmpty()) null else PagingUtils.createCursor(sliceStart + edges.size - 1)
                    }
                    field(PageInfoSelection::hasNextPage, if (first != null) sliceEnd < totalCount else false)
                    field(PageInfoSelection::hasPreviousPage, sliceStart > 0)
                }
            }
        }
    }
}
