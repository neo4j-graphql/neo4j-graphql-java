package org.neo4j.graphql.examples.dgsspringboot.config

import com.netflix.graphql.dgs.DgsCodeRegistry
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsRuntimeWiring
import com.netflix.graphql.dgs.DgsTypeDefinitionRegistry
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.DataFetchingInterceptor
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import javax.annotation.PostConstruct


/**
 * Configuration of the GraphQL schemas
 */
@DgsComponent
open class GraphQLConfiguration {

    @Value("classpath:neo4j.graphql")
    lateinit var graphQl: Resource

    @Autowired(required = false)
    lateinit var dataFetchingInterceptor: DataFetchingInterceptor

    lateinit var schemaBuilder: SchemaBuilder

    @PostConstruct
    fun postConstruct() {
        val schema = graphQl.inputStream.bufferedReader().use { it.readText() }
        val typeDefinitionRegistry = SchemaParser().parse(schema)
        schemaBuilder = SchemaBuilder(typeDefinitionRegistry, SchemaConfig(
                pluralizeFields = true,
                useWhereFilter = true,
                queryOptionStyle = SchemaConfig.InputStyle.INPUT_TYPE,
                mutation = SchemaConfig.CRUDConfig(enabled = false))
        )
        schemaBuilder.augmentTypes()
    }

    @DgsTypeDefinitionRegistry
    fun registry(): TypeDefinitionRegistry {
        return schemaBuilder.typeDefinitionRegistry
    }

    @DgsCodeRegistry
    fun codeRegistry(codeRegistryBuilder: GraphQLCodeRegistry.Builder, registry: TypeDefinitionRegistry): GraphQLCodeRegistry.Builder {
        schemaBuilder.registerDataFetcher(codeRegistryBuilder, dataFetchingInterceptor, registry)
        return codeRegistryBuilder
    }

    @DgsRuntimeWiring
    fun runtimeWiring(builder: RuntimeWiring.Builder): RuntimeWiring.Builder {
        schemaBuilder.registerTypeNameResolver(builder)
        schemaBuilder.registerScalars(builder)
        return builder
    }
}
