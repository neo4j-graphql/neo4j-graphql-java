package org.neo4j.graphql

data class SchemaConfig @JvmOverloads constructor(
        val query: CRUDConfig = CRUDConfig(),
        val mutation: CRUDConfig = CRUDConfig()
) {
    data class CRUDConfig(val enabled: Boolean = true, val exclude: List<String> = emptyList())
}