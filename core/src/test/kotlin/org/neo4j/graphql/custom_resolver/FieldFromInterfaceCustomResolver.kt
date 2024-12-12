package org.neo4j.graphql.custom_resolver

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

@TestDataFetcher(type = "User", field = "customResolverField")
object FieldFromInterfaceCustomResolver : DataFetcher<Int> {
    override fun get(environment: DataFetchingEnvironment): Int {
        val source = environment.getSource<Map<*, *>>()
        var count = 0

        val firstName = source?.get("firstName") as String
        val followedAuthors = source["followedAuthors"] as List<*>
        count += firstName.length
        followedAuthors
            .filterIsInstance<Map<*, *>>()
            .forEach { author ->
                val name = author["name"] as String
                val publications = author["publications"] as List<*>
                count += name.length
                publications
                    .filterIsInstance<Map<*, *>>()
                    .forEach { publication ->
                        val publicationName = publication["name"] as? String
                        val subject = publication["subject"] as? String
                        val publicationYear = publication["publicationYear"] as Number
                        publicationName?.let { count += it.length }
                        subject?.let { count += it.length }
                        count += publicationYear.toInt()
                    }
            }
        return count
    }
}
