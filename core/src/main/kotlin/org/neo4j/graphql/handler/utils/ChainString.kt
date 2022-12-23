package org.neo4j.graphql.handler.utils

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.graphql.SchemaConfig

class ChainString private constructor(val schemaConfig: SchemaConfig, val list: List<Any?>) {

    constructor(schemaConfig: SchemaConfig, vararg parts: Any?) : this(schemaConfig, parts.toList())

    fun extend(parts: List<Any?>) = ChainString(schemaConfig, list + parts)
    fun extend(vararg parts: Any?) = extend(parts.toList())

    fun resolveName() = schemaConfig.namingStrategy.resolveName(*list.toTypedArray())

    fun resolveParameter(value: Any?) = Cypher.parameter(
        schemaConfig.namingStrategy.resolveParameter(*list.toTypedArray()),
        value
    )

    override fun toString(): String = resolveName()

    companion object {
        fun ChainString?.extend(schemaConfig: SchemaConfig, vararg parts: Any?) =
            this?.extend(parts) ?: ChainString(schemaConfig, *parts)
    }
}
