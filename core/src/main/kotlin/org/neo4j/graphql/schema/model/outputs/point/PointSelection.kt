package org.neo4j.graphql.schema.model.outputs.point

import org.neo4j.graphql.Constants
import org.neo4j.graphql.utils.IResolveTree

class PointSelection(selection: IResolveTree) : BasePointSelection(selection) {

    val longitude = selection.getFieldOfType(Constants.POINT_TYPE, Constants.LONGITUDE)

    val latitude = selection.getFieldOfType(Constants.POINT_TYPE, Constants.LATITUDE)

    val height = selection.getFieldOfType(Constants.POINT_TYPE, Constants.HEIGHT)

    override val relevantFields =
        super.relevantFields + setOf(Constants.LONGITUDE, Constants.LATITUDE, Constants.HEIGHT)
}
