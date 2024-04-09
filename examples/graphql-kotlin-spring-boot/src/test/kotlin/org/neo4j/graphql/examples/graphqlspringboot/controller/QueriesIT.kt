package org.neo4j.graphql.examples.graphqlspringboot.controller

import com.expediagroup.graphql.server.types.GraphQLRequest
import org.junit.jupiter.api.Test
import org.neo4j.driver.AuthToken
import org.neo4j.driver.AuthTokens
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.neo4j.Neo4jConnectionDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.containers.Neo4jContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.URI

/**
 * Integration test of example
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration
@Testcontainers
internal class QueriesIT(
    @Autowired private val testClient: WebTestClient
) {
    @Test
    fun `test additional method`() {
        assertQuery(
            """
                    {
                        echo(string: "hello world")
                    }
                """.trimIndent(),
            """
                {
                    "data": {
                        "echo": "hello world"
                    }
                }
                """.trimIndent()
        )
    }

    @Test
    fun `test additional method with pojo`() {
        assertQuery(
            """
                    {
                        pojo(param: "hello world") {
                            id
                        }
                    }
                """.trimIndent(),
            """
                {
                    "data": {
                        "pojo": {
                            "id": "hello world"
                        }
                    }
                }
                """.trimIndent()
        )
    }

    @Test
    fun `test neo4jQuery`() {
        assertQuery(
            """
                mutation {
                  createTeam(id: "team1", name: "Team 1") {
                    id
                  }
                }
                """.trimIndent(),
            """
                {
                    "data": {
                        "createTeam": { "id": "team1" }
                    }
                }
                """.trimIndent()
        )
        assertQuery(
            "{ team { id } }",
            """
                {
                    "data": {
                        "team": [ { "id": "team1" } ]
                    }
                }
                """.trimIndent()
        )
    }

    private fun assertQuery(query: String, expectedResult: String) {
        val request = GraphQLRequest(query = query)
        testClient.post()
            .uri("/graphql")
            .accept(MediaType.APPLICATION_JSON)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .exchange()
            .expectBody().json(expectedResult)
    }

    companion object {
        @Container
        private val neo4jServer = Neo4jContainer<Nothing>("neo4j:5.18.0")

    }

    @TestConfiguration
    open class Config {

        @Bean
        open fun neo4jConnectionDetails() = object :Neo4jConnectionDetails {
            override fun getUri(): URI = URI.create(neo4jServer.boltUrl)
            override fun getAuthToken(): AuthToken = AuthTokens.basic("neo4j",neo4jServer.adminPassword)
        }

    }

}
