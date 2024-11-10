package org.neo4j.graphql.schema.model.outputs.point

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asCypherLiteral
import org.neo4j.graphql.utils.IResolveTree

class CartesianPointSelection(
    selection: IResolveTree,
    crs: List<IResolveTree>,
    srid: List<IResolveTree>,
    val x: List<IResolveTree>,
    val y: List<IResolveTree>,
    val z: List<IResolveTree>,
) : BasePointSelection<PointSelection>(TYPE_NAME, selection, crs, srid) {

    override val relevantFields =
        super.relevantFields + listOf(
            CartesianPointSelection::x,
            CartesianPointSelection::y,
            CartesianPointSelection::z
        ).map { it.name }

    override fun ensureValidity(name: String, point: Expression): Expression {
        return if (name == CartesianPointSelection::z.name) {
            Cypher.caseExpression().`when`(
                point.property(BasePointSelection<*>::srid.name)
                    .isEqualTo(Constants.SpatialReferenceIdentifier.cartesian_3D.asCypherLiteral())
            )
                .then(point.property(name))
                .elseDefault(Cypher.literalNull())
        } else {
            point.property(name)
        }
    }

    companion object {

        val TYPE_NAME = Constants.Types.CARTESIAN_POINT.name

        fun parse(selection: IResolveTree): CartesianPointSelection {
            return CartesianPointSelection(
                selection,
                selection.getFieldOfType(TYPE_NAME, CartesianPointSelection::crs),
                selection.getFieldOfType(TYPE_NAME, CartesianPointSelection::srid),
                selection.getFieldOfType(TYPE_NAME, CartesianPointSelection::x),
                selection.getFieldOfType(TYPE_NAME, CartesianPointSelection::y),
                selection.getFieldOfType(TYPE_NAME, CartesianPointSelection::z),
            )
        }
    }
}
