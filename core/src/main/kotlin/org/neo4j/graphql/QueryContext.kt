package org.neo4j.graphql

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Parameter
import org.neo4j.graphql.handler.utils.ChainString
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

data class QueryContext @JvmOverloads constructor(
    /**
     * if true the <code>__typename</code> will always be returned for interfaces, no matter if it was queried or not
     */
    var queryTypeOfInterfaces: Boolean = false,

    /**
     * If set alternative approaches for query translation will be used
     */
    var optimizedQuery: Set<OptimizationStrategy>? = null,

    var contextParams: Map<String, Any?>? = emptyMap(),
    val auth: AuthParams? = null // TODO init
) {

    private var varCounter = 0
    private var paramCounter = mutableMapOf<String, AtomicInteger>()
    private var paramKeysPerValues = mutableMapOf<String, MutableMap<Any?, Parameter<*>>>()

    fun resolve(string: String): String {
        return contextParams?.let { params ->
            CONTEXT_VARIABLE_PATTERN.replace(string) {
                val path = it.groups[1] ?: it.groups[2] ?: throw IllegalStateException("expected a group")
                val parts = path.value.split(".")
                var o: Any = params
                for (part in parts) {
                    if (o is Map<*, *>) {
                        o = o[part] ?: return@replace ""
                    } else {
                        TODO("only maps are currently supported")
                    }
                }
                return@replace o.toString()
            }
        } ?: string
    }

    fun getNextVariable(prefix: ChainString? = null) = getNextVariable(prefix?.resolveName())
    fun getNextVariable(prefix: String?) = (prefix ?: "var").let { p ->
        varCounter++
            .let { Cypher.name(p + it) }
    }

    fun getNextParam(value: Any?) = getNextParam("param", value)
    fun getNextParam(prefix: String, value: Any?) =
        paramKeysPerValues.computeIfAbsent(prefix) { mutableMapOf() }
            .computeIfAbsent(value) {
                paramCounter
                    .computeIfAbsent(prefix) { AtomicInteger(0) }.getAndIncrement()
                    .let { Cypher.parameter(prefix + it, value) }
            }

    enum class OptimizationStrategy {
        /**
         * If used, filter queries will be converted to cypher matches
         */
        FILTER_AS_MATCH
    }

    class AuthParams(
        val roles: Collection<String>? = null,
        val isAuthenticated: Boolean
    )

    companion object {
        private const val PATH_PATTERN = "([a-zA-Z_][a-zA-Z_0-9]*(?:.[a-zA-Z_][a-zA-Z_0-9]*)*)"
        private val CONTEXT_VARIABLE_PATTERN = Regex("\\\$(?:\\{$PATH_PATTERN}|$PATH_PATTERN)")
    }
}
