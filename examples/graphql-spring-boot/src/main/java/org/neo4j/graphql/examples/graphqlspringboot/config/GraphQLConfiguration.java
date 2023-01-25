package org.neo4j.graphql.examples.graphqlspringboot.config;

import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.neo4j.graphql.DataFetchingInterceptor;
import org.neo4j.graphql.SchemaBuilder;
import org.neo4j.graphql.SchemaConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.graphql.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class GraphQLConfiguration {

    @Value("classpath:neo4j.graphql")
    public Resource graphQl;

    @Autowired(required = false)
    public DataFetchingInterceptor dataFetchingInterceptor;

    @Bean
    public GraphQlSourceBuilderCustomizer graphQlSourceBuilderCustomizer() throws IOException {

        String schema = new String(graphQl.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        TypeDefinitionRegistry neo4jTypeDefinitionRegistry = new SchemaParser().parse(schema);
        SchemaBuilder schemaBuilder = new SchemaBuilder(neo4jTypeDefinitionRegistry, new SchemaConfig(
                new SchemaConfig.CRUDConfig(),
                new SchemaConfig.CRUDConfig(false, List.of()),
                false, true, SchemaConfig.InputStyle.INPUT_TYPE, true, false));
        schemaBuilder.augmentTypes();

        return builder -> builder
                .configureRuntimeWiring(runtimeWiringBuilder -> {
                    schemaBuilder.registerTypeNameResolver(runtimeWiringBuilder);
                    schemaBuilder.registerScalars(runtimeWiringBuilder);
                    GraphQLCodeRegistry.Builder codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry();
                    schemaBuilder.registerDataFetcher(codeRegistryBuilder, dataFetchingInterceptor);
                    runtimeWiringBuilder.codeRegistry(codeRegistryBuilder);
                })
                .schemaFactory((typeDefinitionRegistry, runtimeWiring) -> {
                    typeDefinitionRegistry.merge(neo4jTypeDefinitionRegistry);
                    return new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);
                });
    }
}
