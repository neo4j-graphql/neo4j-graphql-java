package org.neo4j.graphql.examples.dgsspringboot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
open class DgsSpringBootApplication

fun main(args: Array<String>) {
    runApplication<DgsSpringBootApplication>(*args)
}
