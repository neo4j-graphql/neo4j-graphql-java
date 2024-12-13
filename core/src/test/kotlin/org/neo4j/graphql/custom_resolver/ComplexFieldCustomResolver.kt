package org.neo4j.graphql.custom_resolver

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

@TestDataFetcher(type = "User", field = "fullName")
object ComplexFieldCustomResolver : DataFetcher<String> {
    override fun get(environment: DataFetchingEnvironment): String {
        val source = environment.getSource<Map<*, *>>()
        val firstName = source?.get("firstName")
        val lastName = source?.get("lastName")
        val address = source?.get("address") as? Map<*, *>
        val city = address?.get("city")
        return "$firstName $lastName from $city"
    }
}
