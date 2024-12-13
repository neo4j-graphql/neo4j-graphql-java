package org.neo4j.graphql.examples.graphqlspringboot.datafetcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.neo4j.Neo4jConnectionDetails;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureHttpGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.graphql.test.tester.WebGraphQlTester;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;

@AutoConfigureHttpGraphQlTester
@SpringBootTest(properties = "database=neo4j")
@EnableAutoConfiguration
@Testcontainers
class AdditionalDataFetcherTest {

    @Autowired
    private WebGraphQlTester graphQlTester;

    @Autowired
    private Driver driver;

    @Container
    private static final Neo4jContainer<?> neo4jServer = new Neo4jContainer<>("neo4j:5.24.1");


    @BeforeEach
    public void setup() {
        driver.session().run("""
                CREATE (:Movie {title:'The Matrix', released:1999, tagline:'Welcome to the Real World'})
                CREATE (:Movie {title:'The Matrix Reloaded', released:2003, tagline:'Free your mind'})
                CREATE (:Movie {title:'The Matrix Revolutions', released:2003, tagline:'Everything that hasa beginning has an end'})
                """);
    }

    @AfterEach
    public void tearDown() {
        driver.session().run("MATCH (n) DETACH DELETE n");
    }

    @Test
    void testHybridDataFetcher() {
        this.graphQlTester.document("""
                        query {
                          other
                          movies(options: { limit: 3, offset: null }) {
                            title
                            bar
                            javaData {
                              name
                            }
                          }
                        }""")
                .execute()
                .path("")
                .matchesJson("""
                        {
                          "other": "other",
                          "movies": [
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
                        }""");
    }

    @TestConfiguration
    static class Config {

        @Bean
        public Neo4jConnectionDetails neo4jConnectionDetails() {
            return new Neo4jConnectionDetails() {
                @Override
                public URI getUri() {
                    return URI.create(neo4jServer.getBoltUrl());
                }

                @Override
                public AuthToken getAuthToken() {
                    return AuthTokens.basic("neo4j", neo4jServer.getAdminPassword());
                }
            };
        }
    }
}
