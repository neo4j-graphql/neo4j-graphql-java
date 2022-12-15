package org.neo4j.graphql

import org.neo4j.cypherdsl.core.Named
import org.neo4j.cypherdsl.core.SymbolicName
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.BaseField
import org.neo4j.graphql.handler.utils.ChainString

class NamingStrategy {

    fun resolveName(vararg parts: Any?): String {
        return resolveParameter(*parts)
    }

    fun resolveParameter(vararg parts: Any?): String {
        return parts
            .filterNotNull()
            .flatMap {
                when (it) {
                    is ChainString -> it.list
                    else -> listOf(it)
                }
            }
            .filterNotNull()
            .map {
                when (it) {
                    is SymbolicName -> it.value
                    is Named -> it.requiredSymbolicName.value
                    is BaseField -> it.fieldName
                    is Node -> it.name
                    else -> it.toString()
                }
            }
            .filter { it.isNotBlank() }
            .joinToString("_")
            .replace(Regex("_(\\d)"), "$1")
    }
}
