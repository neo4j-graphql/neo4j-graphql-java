package org.neo4j.graphql.custom_resolver

import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment

@TestDataFetcher(type = "Author", field = "publicationsWithAuthor")
object ArrayFieldCustomResolver2 : DataFetcher<List<String>> {
    override fun get(environment: DataFetchingEnvironment): List<String> {
        val source = environment.getSource<Map<*, *>>()
        val name = source?.get("name")
        val publications = source?.get("publications") as? List<*>

        return publications
            ?.filterIsInstance<Map<*, *>>()
            ?.map { publication ->
                val title = publication["title"] ?: publication["subject"]
                val publicationYear = publication["publicationYear"]
                "$title by $name in $publicationYear"
            }
            ?: emptyList()
    }
}
