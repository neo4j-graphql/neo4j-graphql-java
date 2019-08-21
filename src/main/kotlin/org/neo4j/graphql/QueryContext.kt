package org.neo4j.graphql

import graphql.language.FragmentDefinition

data class QueryContext @JvmOverloads constructor(
        val topLevelWhere: Boolean = true,
        val fragments: Map<String, FragmentDefinition> = emptyMap()
)