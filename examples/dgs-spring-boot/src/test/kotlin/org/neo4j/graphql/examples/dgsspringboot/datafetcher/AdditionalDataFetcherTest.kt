package org.neo4j.graphql.examples.dgsspringboot.datafetcher

import com.netflix.graphql.dgs.DgsQueryExecutor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.Driver
import org.neo4j.driver.springframework.boot.autoconfigure.Neo4jDriverProperties
import org.skyscreamer.jsonassert.JSONAssert
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = ["database=neo4j"])
@EnableAutoConfiguration
@Testcontainers
internal class AdditionalDataFetcherTest(
        @Autowired
        val dgsQueryExecutor: DgsQueryExecutor,
        @Autowired
        val driver: Driver
) {

    @BeforeEach
    fun setup() {
        driver.session().use {
            it.run("""
            CREATE (:Movie {title:'The Matrix', released:1999, tagline:'Welcome to the Real World'})
            CREATE (:Movie {title:'The Matrix Reloaded', released:2003, tagline:'Free your mind'})
            CREATE (:Movie {title:'The Matrix Revolutions', released:2003, tagline:'Everything that has a beginning has an end'})
            """.trimIndent())
        }
    }

    @AfterEach
    fun tearDown() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n") }
    }

    @Test
    fun testHybridDataFetcher() {
        val response = dgsQueryExecutor.executeAndGetDocumentContext("""
        query{
          other
          movie{
            title    
            bar
            javaData {
              name
            }
          }
        }
        """.trimIndent())

        //language=JSON
        JSONAssert.assertEquals("""
        {
          "data": {
            "other": "other",
            "movie": [
              {
                "title": "The Matrix",
                "bar": "foo",
                "javaData": [
                  {
                    "name": "test The Matrix"
                  }
                ]
              },
              {
                "title": "The Matrix Reloaded",
                "bar": "foo",
                "javaData": [
                  {
                    "name": "test The Matrix Reloaded"
                  }
                ]
              },
              {
                "title": "The Matrix Revolutions",
                "bar": "foo",
                "javaData": [
                  {
                    "name": "test The Matrix Revolutions"
                  }
                ]
              }
            ]
          }
        }
      """.trimIndent(), response.jsonString(), true)
    }

    @TestConfiguration
    open class Config {

        @Bean
        @ConfigurationProperties(prefix = "ignore")
        @Primary
        open fun properties(): Neo4jDriverProperties {
            val properties = Neo4jDriverProperties()
            properties.uri = URI.create(neo4jServer.boltUrl)
            properties.authentication = Neo4jDriverProperties.Authentication()
            properties.authentication.username = "neo4j"
            properties.authentication.password = neo4jServer.adminPassword
            return properties
        }
    }

    companion object {
        @Container
        private val neo4jServer = Neo4jContainer<Nothing>("neo4j:4.2.4")

    }
}
