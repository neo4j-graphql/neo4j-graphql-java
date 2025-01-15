package org.neo4j.graphql

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Parameter
import org.neo4j.graphql.domain.fields.RelationField
import java.util.concurrent.atomic.AtomicInteger

data class QueryContext @JvmOverloads constructor(
    val contextParams: Map<String, Any?>? = emptyMap(),
    /**
     * Parameters to be used when querying with Cypher.
     *
     * To be used with directives such as `@node`, and can be used directly as named here.
     */
    val cypherParams: Map<String, Any?>? = emptyMap(),
) {

    private var varCounter = mutableMapOf<String, AtomicInteger>()
    private var paramCounter = mutableMapOf<String, AtomicInteger>()
    private var paramKeysPerValues = mutableMapOf<String, MutableMap<Any?, Parameter<*>>>()

    fun resolve(string: String, useCypherParams: Boolean = false): String {
        val lookups = listOfNotNull(
            contextParams,
            cypherParams?.takeIf { useCypherParams }
        )
        return resolve(string, lookups)
    }

    private fun resolve(string: String, lookups: List<Map<String, Any?>?>): String {
        return CONTEXT_VARIABLE_PATTERN.replace(string) {
            val path = it.groups[1] ?: it.groups[2] ?: throw IllegalStateException("expected a group")
            val parts = path.value.split(".")
            for (lookup in lookups) {
                val value = getValue(parts, lookup)
                if (value != null) {
                    return@replace value.toString()
                }
            }
            return@replace ""
        }
    }

    private fun getValue(parts: List<String>, root: Map<String, Any?>?): Any? {
        var o: Any? = null
        for ((index, part) in parts.withIndex()) {
            if (index == 0) {
                if (part == "context") {
                    o = root
                    continue
                } else {
                    o = root
                }
            }
            if (o is Map<*, *>) {
                o = o[part] ?: return null
            } else {
                TODO("only maps are currently supported")
            }
        }
        return o
    }

    fun getNextVariable(relationField: RelationField) = getNextVariable(
        relationField.relationType.toLowerCase().toCamelCase()
    )

    fun getNextVariable(node: org.neo4j.graphql.domain.Node) = getNextVariable(node.name.decapitalize())

    fun getNextVariable(prefix: String?) = getNextVariableName(prefix).let { Cypher.name(it) }
    fun getNextVariableName(prefix: String?) = (prefix ?: "var").let { p ->
        varCounter.computeIfAbsent(p) { AtomicInteger(0) }.getAndIncrement()
            .let { p + it }
    }

    fun getNextParam(value: Any?) = getNextParam("param", value)
    private fun getNextParam(prefix: String, value: Any?, reuseKey: Boolean = false): Parameter<*> =
        if (reuseKey) {
            paramKeysPerValues.computeIfAbsent(prefix) { mutableMapOf() }
                .computeIfAbsent(value) {
                    getNextParam(prefix, value, reuseKey = false)
                }
        } else {
            paramCounter
                .computeIfAbsent(prefix) { AtomicInteger(0) }.getAndIncrement()
                .let { Cypher.parameter(prefix + it, value) }
        }


    companion object {
        const val KEY = "Neo4jGraphQLQueryContext"

        private const val PATH_PATTERN = "([a-zA-Z_][a-zA-Z_0-9]*(?:.[a-zA-Z_][a-zA-Z_0-9]*)*)"

        // matches ${path} or $path
        private val CONTEXT_VARIABLE_PATTERN = Regex("\\\$(?:\\{$PATH_PATTERN}|$PATH_PATTERN)")
    }
}
