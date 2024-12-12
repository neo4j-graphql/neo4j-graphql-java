package org.neo4j.graphql.custom_resolver

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

@TestDataFetcher(type = "User", field = "fullName")
object ComplexFieldCustomResolver2 : DataFetcher<String> {
    override fun get(environment: DataFetchingEnvironment): String {
        val source = environment.getSource<Map<*, *>>()
        val firstName = source?.get("firstName")
        val lastName = source?.get("lastName")

        val address = source?.get("address") as? Map<*, *>

        val city = address?.get("city") as? Map<*, *>
        val name = city?.get("name")
        val population = city?.get("population")

        return "$firstName $lastName from $name with population of $population"
    }
}
