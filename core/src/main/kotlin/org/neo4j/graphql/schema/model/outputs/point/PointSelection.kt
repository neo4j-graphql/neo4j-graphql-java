package org.neo4j.graphql.schema.model.outputs.point

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.Constants
import org.neo4j.graphql.asCypherLiteral
import org.neo4j.graphql.utils.IResolveTree

class PointSelection(
    selection: IResolveTree,
    crs: List<IResolveTree>,
    srid: List<IResolveTree>,
    val longitude: List<IResolveTree>,
    val latitude: List<IResolveTree>,
    val height: List<IResolveTree>,
) : BasePointSelection<PointSelection>(TYPE_NAME, selection, crs, srid) {

    override val relevantFields =
        super.relevantFields + listOf(
            PointSelection::longitude,
            PointSelection::latitude,
            PointSelection::height
        ).map { it.name }

    override fun ensureValidity(name: String, point: Expression): Expression {
        return if (name == PointSelection::height.name) {
            Cypher.caseExpression().`when`(
                point.property(BasePointSelection<*>::srid.name)
                    .isEqualTo(Constants.SpatialReferenceIdentifier.wgs_84_3D.asCypherLiteral())
            )
                .then(point.property(name))
                .elseDefault(Cypher.literalNull())
        } else {
            point.property(name)
        }
    }

    companion object {

        val TYPE_NAME = Constants.Types.POINT.name

        fun parse(selection: IResolveTree): PointSelection {
            return PointSelection(
                selection,
                selection.getFieldOfType(TYPE_NAME, PointSelection::crs),
                selection.getFieldOfType(TYPE_NAME, PointSelection::srid),
                selection.getFieldOfType(TYPE_NAME, PointSelection::longitude),
                selection.getFieldOfType(TYPE_NAME, PointSelection::latitude),
                selection.getFieldOfType(TYPE_NAME, PointSelection::height),
            )
        }
    }
}
