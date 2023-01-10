package org.neo4j.graphql.handler.utils

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.graphql.SchemaConfig

class ChainString private constructor(val schemaConfig: SchemaConfig, val list: List<Pair<Boolean, Any?>>) {

    constructor(schemaConfig: SchemaConfig, vararg parts: Any?) : this(schemaConfig, parts.toList().map { false to it })

    fun extend(parts: List<Any?>) = ChainString(schemaConfig, list + parts.map { false to it })
    fun extend(vararg parts: Any?) = extend(parts.toList())

    fun appendOnPrevious(parts: List<Any?>) = ChainString(schemaConfig, list + parts.map { true to it })

    fun appendOnPrevious(vararg parts: Any?) = appendOnPrevious(parts.toList())

    fun resolveName() = schemaConfig.namingStrategy.resolveInternal(list)

    fun resolveParameter(value: Any?) = Cypher.parameter(
        schemaConfig.namingStrategy.resolveInternal(list),
        value
    )

    override fun toString(): String = resolveName()

    companion object {
        fun ChainString?.extend(schemaConfig: SchemaConfig, vararg parts: Any?) =
            this?.extend(parts) ?: ChainString(schemaConfig, *parts)
    }
}
