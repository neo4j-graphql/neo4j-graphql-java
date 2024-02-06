package org.neo4j.graphql.schema.model.outputs.point

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asCypherLiteral
import org.neo4j.graphql.utils.IResolveTree

class CartesianPointSelection(selection: IResolveTree) : BasePointSelection(selection) {

    val x = selection.getFieldOfType(Constants.POINT_TYPE, Constants.X)

    val y = selection.getFieldOfType(Constants.POINT_TYPE, Constants.Y)

    val z = selection.getFieldOfType(Constants.POINT_TYPE, Constants.Z)

    override val relevantFields = super.relevantFields + setOf(Constants.X, Constants.Y, Constants.Z)
    override fun ensureValidity(name: String, point: Expression): Expression {
        return if (name == Constants.Z) {
            Cypher.caseExpression().`when`(
                point.property(Constants.SRID)
                    .isEqualTo(Constants.SpatialReferenceIdentifier.cartesian_3D.asCypherLiteral())
            )
                .then(point.property(name))
                .elseDefault(Cypher.literalNull())
        } else {
            point.property(name)
        }
    }
}
