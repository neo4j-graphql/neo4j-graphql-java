package org.neo4j.graphql.examples.dgsspringboot.datafetcher

import com.jayway.jsonpath.TypeRef
import com.netflix.graphql.dgs.DgsQueryExecutor
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.neo4j.driver.AuthToken
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.graphql.examples.dgsspringboot.types.DgsClient
import org.neo4j.graphql.examples.dgsspringboot.types.DgsConstants
import org.neo4j.graphql.examples.dgsspringboot.types.types.Movie
import org.neo4j.graphql.examples.dgsspringboot.types.types.MovieOptions
import org.neo4j.graphql.examples.dgsspringboot.types.types.MovieSort
import org.neo4j.graphql.examples.dgsspringboot.types.types.SortDirection
import org.skyscreamer.jsonassert.JSONAssert
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.neo4j.Neo4jConnectionDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
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
            it.run(
                """
            CREATE (:Movie {title:'The Matrix', released:1999, tagline:'Welcome to the Real World'})
            CREATE (:Movie {title:'The Matrix Reloaded', released:2003, tagline:'Free your mind'})
            CREATE (:Movie {title:'The Matrix Revolutions', released:2003, tagline:'Everything that has a beginning has an end'})
            """.trimIndent()
            )
        }
    }

    @AfterEach
    fun tearDown() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n") }
    }

    @Test
    fun testHybridDataFetcher() {
        val request = DgsClient.buildQuery {
            movies(options = MovieOptions(sort = listOf(MovieSort(title = SortDirection.DESC)))) {
                bar
                javaData {
                    name
                }
            }
        }

        Assertions.assertThat(request)
            .isEqualTo(
                """
                {
                  __typename
                  movies(options: {sort : [{title : DESC}]}) {
                    __typename
                    bar
                    javaData {
                      __typename
                      name
                    }
                  }
                }
                """.trimIndent() + "\n"
            )

        val response = dgsQueryExecutor.executeAndGetDocumentContext(request)

        //language=JSON
        JSONAssert.assertEquals(
            """
        {
          "data": {
            "__typename": "Query",
            "movies": [
              {
                "__typename": "Movie",
                "bar": "foo",
                "javaData": [
                  {
                    "__typename": "JavaData",
                    "name": "test The Matrix Revolutions"
                  }
                ]
              },
              {
                "__typename": "Movie",
                "bar": "foo",
                "javaData": [
                  {
                    "__typename": "JavaData",
                    "name": "test The Matrix Reloaded"
                  }
                ]
              },
              {
                "__typename": "Movie",
                "bar": "foo",
                "javaData": [
                  {
                    "__typename": "JavaData",
                    "name": "test The Matrix"
                  }
                ]
              }
            ]
          }
        }
      """.trimIndent(), response.jsonString(), true
        )

        val list = response.read("data.${DgsConstants.QUERY.Movies}", object : TypeRef<List<Movie>>() {})
        Assertions.assertThat(list).hasSize(3)
    }

    @TestConfiguration
    open class Config {

        @Bean
        open fun neo4jConnectionDetails() = object : Neo4jConnectionDetails {
            override fun getUri(): URI = URI.create(neo4jServer.boltUrl)
            override fun getAuthToken(): AuthToken = AuthTokens.basic("neo4j", neo4jServer.adminPassword)
        }

    }

    companion object {
        @Container
        private val neo4jServer = Neo4jContainer<Nothing>("neo4j:5.24.1")
    }
}
