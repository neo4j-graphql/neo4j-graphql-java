package org.neo4j.graphql

data class SchemaConfig @JvmOverloads constructor(
        val query: CRUDConfig = CRUDConfig(),
        val mutation: CRUDConfig = CRUDConfig(),
        /**
         * if true, the top level fields of the Query-type will be capitalized
         */
        val capitalizeQueryFields: Boolean = false
) {
    data class CRUDConfig(val enabled: Boolean = true, val exclude: List<String> = emptyList())
}
