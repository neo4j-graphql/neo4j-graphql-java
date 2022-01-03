package org.neo4j.graphql.examples.graphqlspringboot.datafetcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.springframework.boot.autoconfigure.Neo4jDriverProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureWebGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.graphql.test.tester.WebGraphQlTester;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;

@AutoConfigureWebGraphQlTester
@SpringBootTest(properties = "database=neo4j")
@EnableAutoConfiguration
@Testcontainers
class AdditionalDataFetcherTest {

    @Autowired
    private WebGraphQlTester graphQlTester;

    @Autowired
    private Driver driver;

    @Container
    private static final Neo4jContainer<?> neo4jServer = new Neo4jContainer<>("neo4j:4.4.1");


    @BeforeEach
    public void setup() {
        driver.session().run(
                "CREATE (:Movie {title:'The Matrix', released:1999, tagline:'Welcome to the Real World'})\n" +
                        "CREATE (:Movie {title:'The Matrix Reloaded', released:2003, tagline:'Free your mind'})\n" +
                        "CREATE (:Movie {title:'The Matrix Revolutions', released:2003, tagline:'Everything that hasa beginning has an end'})");
    }

    @AfterEach
    public void tearDown() {
        driver.session().run("MATCH (n) DETACH DELETE n");
    }

    @Test
    void testHybridDataFetcher() {
        this.graphQlTester.query("query {\n" +
                        "  other\n" +
                        "  movies(options: { limit: 3, skip: null }) {\n" +
                        "    title\n" +
                        "    bar\n" +
                        "    javaData {\n" +
                        "      name\n" +
                        "    }\n" +
                        "  }\n" +
                        "}")
                .execute()
                .path("")
                .matchesJson("{\n" +
                        "  \"other\": \"other\",\n" +
                        "  \"movies\": [\n" +
                        "    {\n" +
                        "      \"title\": \"The Matrix\",\n" +
                        "      \"bar\": \"foo\",\n" +
                        "      \"javaData\": [\n" +
                        "        {\n" +
                        "          \"name\": \"test The Matrix\"\n" +
                        "        }\n" +
                        "      ]\n" +
                        "    },\n" +
                        "    {\n" +
                        "      \"title\": \"The Matrix Reloaded\",\n" +
                        "      \"bar\": \"foo\",\n" +
                        "      \"javaData\": [\n" +
                        "        {\n" +
                        "          \"name\": \"test The Matrix Reloaded\"\n" +
                        "        }\n" +
                        "      ]\n" +
                        "    },\n" +
                        "    {\n" +
                        "      \"title\": \"The Matrix Revolutions\",\n" +
                        "      \"bar\": \"foo\",\n" +
                        "      \"javaData\": [\n" +
                        "        {\n" +
                        "          \"name\": \"test The Matrix Revolutions\"\n" +
                        "        }\n" +
                        "      ]\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}");
    }

    @TestConfiguration
    static class Config {

        @Bean
        @ConfigurationProperties(prefix = "ignore")
        @Primary
        public Neo4jDriverProperties properties() {
            Neo4jDriverProperties properties = new Neo4jDriverProperties();
            properties.setUri(URI.create(neo4jServer.getBoltUrl()));
            Neo4jDriverProperties.Authentication authentication = new Neo4jDriverProperties.Authentication();
            authentication.setUsername("neo4j");
            authentication.setPassword(neo4jServer.getAdminPassword());
            properties.setAuthentication(authentication);
            return properties;
        }
    }
}
