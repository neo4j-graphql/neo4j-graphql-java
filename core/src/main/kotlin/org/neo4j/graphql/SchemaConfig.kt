package org.neo4j.graphql

import com.fasterxml.jackson.annotation.JsonProperty

data class SchemaConfig @JvmOverloads constructor(
    val features: Neo4jFeaturesSettings = Neo4jFeaturesSettings()
) {

    data class Neo4jFeaturesSettings(
        val filters: Neo4jFiltersSettings = Neo4jFiltersSettings(),
    )

    data class Neo4jFiltersSettings(
        // TODO should we also use feature toggles for strings? https://github.com/neo4j/graphql/issues/2657#issuecomment-1369858159
        @field:JsonProperty("String")
        val string: Neo4jStringFiltersSettings = Neo4jStringFiltersSettings(),
        @field:JsonProperty("ID")
        val id: Neo4jIDFiltersSettings = Neo4jIDFiltersSettings()
    )

    data class Neo4jStringFiltersSettings(
        @field:JsonProperty("GT")
        val gt: Boolean = false,
        @field:JsonProperty("GTE")
        val gte: Boolean = false,
        @field:JsonProperty("LT")
        val lt: Boolean = false,
        @field:JsonProperty("LTE")
        val lte: Boolean = false,
        @field:JsonProperty("MATCHES")
        val matches: Boolean = false,
    )

    data class Neo4jIDFiltersSettings(
        @field:JsonProperty("MATCHES")
        val matches: Boolean = false,
    )
}
