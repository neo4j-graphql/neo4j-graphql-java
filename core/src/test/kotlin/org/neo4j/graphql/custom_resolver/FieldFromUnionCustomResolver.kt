package org.neo4j.graphql.custom_resolver

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

@TestDataFetcher(type = "User", field = "customResolverField")
object FieldFromUnionCustomResolver : DataFetcher<Int> {
    override fun get(environment: DataFetchingEnvironment): Int {
        val source = environment.getSource<Map<*, *>>()
        var count = 0

        val firstName = source?.get("firstName") as String
        val followedAuthors = source["followedAuthors"] as List<*>
        count += firstName.length
        followedAuthors.forEach { author ->
            author as Map<*, *>
            count += (author["name"] as String).length
            val publications = author["publications"] as List<*>
            publications.forEach { publication ->
                publication as Map<*, *>
                val publicationName = publication["name"] as? String
                val subject = publication["subject"] as? String
                publicationName?.let { count += it.length }
                subject?.let { count += it.length }
            }
        }
        return count
    }
}
