package org.neo4j.graphql.examples.graphqlspringboot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
open class GraphqlSpringBootApplication

fun main(args: Array<String>) {
    runApplication<GraphqlSpringBootApplication>(*args)
}
