package org.neo4j.graphql.custom_resolver

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

@TestDataFetcher(type = "User", field = "fullName")
object ScalarFieldCustomResolver : DataFetcher<String> {
    override fun get(environment: DataFetchingEnvironment): String {
        val source = environment.getSource<Map<String, *>>()
        val firstName = source?.get("firstName")
        val lastName = source?.get("lastName")
        return "$firstName $lastName"
    }
}
