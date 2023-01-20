package org.neo4j.graphql

import org.neo4j.cypherdsl.core.Named
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.utils.ResolveTree

class NamingStrategy {

    fun resolveName(vararg parts: Any?): String {
        return resolveInternal(parts.map { false to it })
    }

    fun resolveParameter(vararg parts: Any?): String {
        return resolveInternal(parts.map { false to it })
    }

    fun resolveInternal(parts: List<Pair<Boolean, Any?>>): String {
        return parts
            .flatMap { (appendOnPrevious, value) ->
                when (value) {
                    is ChainString -> value.list
                    else -> listOf(appendOnPrevious to value)
                }
            }
            .fold("", { result, (appendOnPrevious, value) ->
                val string = when (value) {
                    is SymbolicName -> value.value
                    is Named -> value.requiredSymbolicName.value
                    is BaseField -> value.fieldName
                    is Node -> value.name
                    is ResolveTree -> value.aliasOrName
                    null -> return@fold result
                    else -> value.toString()
                }
                if (string.isNullOrBlank()) {
                    return@fold result
                }
                var r = result
                if (!appendOnPrevious && result.isNotBlank()) {
                    r += "_"
                }
                return@fold r + string
            })
    }
}
