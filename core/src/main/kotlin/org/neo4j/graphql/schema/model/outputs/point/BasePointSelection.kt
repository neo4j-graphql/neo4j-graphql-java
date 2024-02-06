package org.neo4j.graphql.schema.model.outputs.point

import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.Constants
import org.neo4j.graphql.utils.IResolveTree

sealed class BasePointSelection(val selection: IResolveTree) {

    val crs = selection.getFieldOfType(Constants.POINT_TYPE, Constants.CRS)

    val srid = selection.getFieldOfType(Constants.POINT_TYPE, Constants.SRID)

    protected open val relevantFields = setOf(Constants.CRS, Constants.SRID)
    fun project(prop: Expression): List<Any>? = selection
        .fieldsByTypeName[Constants.POINT_TYPE]
        ?.values
        ?.filter { relevantFields.contains(it.name) }
        ?.flatMap {
            val exp = ensureValidity(it.name, prop)
            listOf(it.aliasOrName, exp)
        }

    abstract fun ensureValidity(name: String, point: Expression): Expression
}
