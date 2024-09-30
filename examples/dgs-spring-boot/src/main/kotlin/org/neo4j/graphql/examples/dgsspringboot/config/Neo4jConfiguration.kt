package org.neo4j.graphql.examples.dgsspringboot.config

import org.neo4j.driver.Driver
import org.neo4j.graphql.driver.adapter.Neo4jAdapter
import org.neo4j.graphql.driver.adapter.Neo4jDriverAdapter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration of the Neo4jAdapter
 */
@Configuration
open class Neo4jConfiguration {

    @Bean
    open fun neo4jAdapter(driver: Driver, @Value("\${database}") database: String): Neo4jAdapter {
        return Neo4jDriverAdapter(driver, Neo4jAdapter.Dialect.NEO4J_5, database)
    }
}
