package org.neo4j.graphql.schema.model.inputs

import graphql.language.ObjectValue
import org.neo4j.graphql.toDict
import org.neo4j.graphql.toJavaValue
import org.neo4j.graphql.wrapList

open class Dict(val map: Map<String, Any?>) : Map<String, Any?> by map {
    fun nestedObject(name: String) = get(name)

    fun nestedDict(name: String) = create(get(name))
    fun nestedDictList(name: String): List<Dict> {
        val value = get(name)
        return value?.wrapList()?.toDict() ?: emptyList()
    }

    companion object {
        val EMPTY: Dict = Dict(emptyMap())

        fun create(data: Any?): Dict? = data?.let {
            val map = when (it) {
                is ObjectValue -> it.objectFields.associate { field -> field.name to field.value.toJavaValue() }
                is Map<*, *> -> it.mapKeys { (key, _) -> key as String }
                else -> error("expected a map with string keys")
            }
            return Dict(map)
        }
    }
}

