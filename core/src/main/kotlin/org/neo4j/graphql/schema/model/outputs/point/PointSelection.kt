package org.neo4j.graphql.schema.model.outputs.point

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asCypherLiteral
import org.neo4j.graphql.utils.IResolveTree

class PointSelection(selection: IResolveTree) : BasePointSelection(selection) {

    val longitude = selection.getFieldOfType(Constants.POINT_TYPE, Constants.LONGITUDE)

    val latitude = selection.getFieldOfType(Constants.POINT_TYPE, Constants.LATITUDE)

    val height = selection.getFieldOfType(Constants.POINT_TYPE, Constants.HEIGHT)

    override val relevantFields =
        super.relevantFields + setOf(Constants.LONGITUDE, Constants.LATITUDE, Constants.HEIGHT)

    override fun ensureValidity(name: String, point: Expression): Expression {
        return if (name == Constants.HEIGHT) {
            Cypher.caseExpression().`when`(
                point.property(Constants.SRID)
                    .isEqualTo(Constants.SpatialReferenceIdentifier.wgs_84_3D.asCypherLiteral())
            )
                .then(point.property(name))
                .elseDefault(Cypher.literalNull())
        } else {
            point.property(name)
        }
    }
}
