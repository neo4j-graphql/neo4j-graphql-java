package org.neo4j.graphql.schema.model.outputs.point

import org.neo4j.graphql.Constants
import org.neo4j.graphql.utils.IResolveTree

class CartesianPointSelection(selection: IResolveTree) : BasePointSelection(selection) {

    val x = selection.getFieldOfType(Constants.POINT_TYPE, Constants.X)

    val y = selection.getFieldOfType(Constants.POINT_TYPE, Constants.Y)

    val z = selection.getFieldOfType(Constants.POINT_TYPE, Constants.Z)

    override val relevantFields = super.relevantFields + setOf(Constants.X, Constants.Y, Constants.Z)
}
