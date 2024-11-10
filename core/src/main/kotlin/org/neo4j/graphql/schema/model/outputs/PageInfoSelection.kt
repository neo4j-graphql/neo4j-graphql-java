package org.neo4j.graphql.schema.model.outputs

import org.neo4j.graphql.Constants
import org.neo4j.graphql.NonNull
import org.neo4j.graphql.schema.AugmentationBase
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.utils.IResolveTree

class PageInfoSelection(
    selection: IResolveTree,
    val startCursor: List<IResolveTree>,
    val endCursor: List<IResolveTree>,
    val hasNextPage: List<IResolveTree>,
    val hasPreviousPage: List<IResolveTree>,
) : BaseSelection<PageInfoSelection>(selection) {


    object Augmentation : AugmentationBase {

        fun generateNodeSelection(ctx: AugmentationContext) =
            ctx.getOrCreateObjectType(TYPE_NAME) { fields, _ ->
                fields += field(PageInfoSelection::startCursor, Constants.Types.String)
                fields += field(PageInfoSelection::endCursor, Constants.Types.String)
                fields += field(PageInfoSelection::hasNextPage, Constants.Types.Boolean.NonNull)
                fields += field(PageInfoSelection::hasPreviousPage, Constants.Types.Boolean.NonNull)
            }!!
    }

    companion object {
        val TYPE_NAME = Constants.Types.PageInfo.name

        fun parse(selection: IResolveTree): PageInfoSelection {
            return PageInfoSelection(
                selection,
                selection.getFieldOfType(TYPE_NAME, PageInfoSelection::startCursor),
                selection.getFieldOfType(TYPE_NAME, PageInfoSelection::endCursor),
                selection.getFieldOfType(TYPE_NAME, PageInfoSelection::hasNextPage),
                selection.getFieldOfType(TYPE_NAME, PageInfoSelection::hasPreviousPage),
            )
        }
    }

}
