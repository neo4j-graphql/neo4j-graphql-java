package org.neo4j.graphql

import org.neo4j.cypherdsl.core.Cypher
import org.neo4j.cypherdsl.core.Parameter
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.driver.adapter.Neo4jAdapter.Dialect
import java.util.concurrent.atomic.AtomicInteger

data class QueryContext @JvmOverloads constructor(
    var neo4jDialect: Dialect = Dialect.NEO4J_5,

    val contextParams: Map<String, Any?>? = emptyMap(),
) {

    private var varCounter = mutableMapOf<String, AtomicInteger>()
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
        private val CONTEXT_VARIABLE_PATTERN = Regex("\\\$(?:\\{$PATH_PATTERN}|$PATH_PATTERN)")
    }
}
