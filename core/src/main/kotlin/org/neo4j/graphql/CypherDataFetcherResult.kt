package org.neo4j.graphql

import graphql.schema.GraphQLType

internal data class CypherDataFetcherResult @JvmOverloads constructor(
    val query: String,
    val params: Map<String, Any?> = emptyMap(),
    var type: GraphQLType? = null,
    val variable: String
)
