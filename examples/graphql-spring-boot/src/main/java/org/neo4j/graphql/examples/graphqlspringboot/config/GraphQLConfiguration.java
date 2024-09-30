package org.neo4j.graphql.examples.graphqlspringboot.config;

import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.neo4j.driver.Driver;
import org.neo4j.graphql.SchemaBuilder;
import org.neo4j.graphql.driver.adapter.Neo4jAdapter;
import org.neo4j.graphql.driver.adapter.Neo4jDriverAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.graphql.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class GraphQLConfiguration {

    @Bean
    public Neo4jAdapter neo4jAdapter(Driver driver, @Value("${database}") String database) {
        return new Neo4jDriverAdapter(driver, Neo4jAdapter.Dialect.NEO4J_5, database);
    }

    @Bean
    public GraphQlSourceBuilderCustomizer graphQlSourceBuilderCustomizer(
            Neo4jAdapter neo4jAdapter,
            @Value("classpath:neo4j.graphql") Resource graphQl
    ) throws IOException {

        String neo4jSchema = graphQl.getContentAsString(StandardCharsets.UTF_8);

        TypeDefinitionRegistry neo4jTypeDefinitionRegistry = new SchemaParser().parse(neo4jSchema);
        SchemaBuilder schemaBuilder = new SchemaBuilder(neo4jTypeDefinitionRegistry);
        schemaBuilder.augmentTypes();

        return builder -> builder
                .configureRuntimeWiring(runtimeWiringBuilder -> {
                    schemaBuilder.registerTypeNameResolver(runtimeWiringBuilder);
                    schemaBuilder.registerScalars(runtimeWiringBuilder);
                    GraphQLCodeRegistry.Builder codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry();
                    schemaBuilder.registerNeo4jAdapter(codeRegistryBuilder, neo4jAdapter);
                    runtimeWiringBuilder.codeRegistry(codeRegistryBuilder);
                })
                .schemaFactory((typeDefinitionRegistry, runtimeWiring) -> {
                    typeDefinitionRegistry.merge(neo4jTypeDefinitionRegistry);
                    return new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);
                });
    }
}
