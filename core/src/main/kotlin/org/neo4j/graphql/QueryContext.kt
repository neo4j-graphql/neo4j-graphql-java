package org.neo4j.graphql

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
