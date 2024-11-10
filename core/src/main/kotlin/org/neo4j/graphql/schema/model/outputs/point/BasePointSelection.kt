package org.neo4j.graphql.schema.model.outputs.point

import org.neo4j.cypherdsl.core.Expression
import org.neo4j.graphql.schema.model.outputs.BaseSelection
import org.neo4j.graphql.utils.IResolveTree

sealed class BasePointSelection<T: BasePointSelection<T>>(
    val typeName: String,
    selection: IResolveTree,
    val crs: List<IResolveTree>,
    val srid: List<IResolveTree>,
): BaseSelection<T>(selection) {

    protected open val relevantFields = listOf(
        BasePointSelection<*>::crs,
        BasePointSelection<*>::srid)
        .map { it.name }
        .toSet()

    fun cypherProject(prop: Expression): List<Any>? = selection
        .fieldsByTypeName[typeName]
        ?.values
        ?.filter { relevantFields.contains(it.name) }
        ?.flatMap {
            val exp = ensureValidity(it.name, prop)
            listOf(it.aliasOrName, exp)
        }

    abstract fun ensureValidity(name: String, point: Expression): Expression
}
