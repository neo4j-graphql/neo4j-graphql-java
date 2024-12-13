package org.neo4j.graphql.custom_resolver

@Target(AnnotationTarget.CLASS)
annotation class TestDataFetcher(
    val type: String,
    val field: String,
)
