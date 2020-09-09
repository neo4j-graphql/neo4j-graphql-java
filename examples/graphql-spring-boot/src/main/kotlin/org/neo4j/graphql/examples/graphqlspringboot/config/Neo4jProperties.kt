package org.neo4j.graphql.examples.graphqlspringboot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties(prefix = "neo4j")
data class Neo4jProperties(
        var uri: URI?,
        var username: String?,
        var password: String?
)
