package org.neo4j.graphql.schema.model.outputs

import org.neo4j.graphql.utils.IResolveTree
import kotlin.reflect.KProperty1

open class BaseSelection<T : BaseSelection<T>>(
    val selection: IResolveTree,
) : IResolveTree by selection {


    inline fun <reified R : T> project(projection: ProjectionBuilder<R>.() -> Unit): Map<String, *> {
        val result = mutableMapOf<String, Any?>()
        val selection = (this as? R
            ?: throw ClassCastException("Cannot cast ${this::class.simpleName} to ${R::class.simpleName}"))
        projection(ProjectionBuilder(selection, result))
        return result
    }

    inner class ProjectionBuilder<T>(val selection: T, val result: MutableMap<String, Any?>) {

        fun field(prop: KProperty1<T, List<IResolveTree>>, value: Any?) {
            prop.get(selection).forEach { field -> result[field.aliasOrName] = value }
        }

        fun lazyField(prop: KProperty1<T, List<IResolveTree>>, value: () -> Any?) {
            prop.get(selection).forEach { field -> result[field.aliasOrName] = value() }
        }

        fun allOf(value: Map<*, *>) {
            value.forEach { (k, v) -> if (k is String) result[k] = v }
        }

        fun <R : BaseSelection<R>> field(
            prop: KProperty1<T, List<BaseSelection<R>>>,
            nestedProjection: BaseSelection<R>.() -> Any?
        ) {
            prop.get(selection).forEach { field -> result[field.aliasOrName] = nestedProjection(field) }
        }

    }

}
