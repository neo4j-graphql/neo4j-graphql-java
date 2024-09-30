package org.neo4j.graphql.examples.graphqlspringboot.config

import com.expediagroup.graphql.generator.SchemaGenerator
import com.expediagroup.graphql.generator.SchemaGeneratorConfig
import com.expediagroup.graphql.generator.TopLevelObject
import com.expediagroup.graphql.generator.extensions.print
import com.expediagroup.graphql.server.operations.Mutation
import com.expediagroup.graphql.server.operations.Query
import com.expediagroup.graphql.server.operations.Subscription
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import org.neo4j.driver.Driver
import org.neo4j.graphql.SchemaBuilder
import org.neo4j.graphql.SchemaConfig
import org.neo4j.graphql.driver.adapter.Neo4jAdapter
import org.neo4j.graphql.driver.adapter.Neo4jDriverAdapter
import org.slf4j.LoggerFactory
import org.springframework.aop.framework.Advised
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.io.Resource
import java.util.*

/**
 * Configuration of the GraphQL schemas
 */
@Configuration
open class GraphQLConfiguration {
    private val logger = LoggerFactory.getLogger(GraphQLConfiguration::class.java)

    @Bean
    open fun neo4jAdapter(driver: Driver): Neo4jAdapter {
        return Neo4jDriverAdapter(driver, Neo4jAdapter.Dialect.NEO4J_5)
    }

    /**
     * This generates the augmented neo4j schema
     */
    @Bean
    open fun neo4jSchema(
        @Value("classpath:schema.graphql") graphQl: Resource,
        @Autowired neo4jAdapter: Neo4jAdapter,
    ): GraphQLSchema {
        val schema = graphQl.inputStream.bufferedReader().use { it.readText() }
        return SchemaBuilder.buildSchema(schema, SchemaConfig(), neo4jAdapter)
    }

    /**
     * This generates the spring schema provided generated by the `graphql-kotlin-spring-server`
     */
    @Bean
    open fun springSchema(
        queries: Optional<List<Query>>,
        mutations: Optional<List<Mutation>>,
        subscriptions: Optional<List<Subscription>>,
        schemaConfig: SchemaGeneratorConfig
    ): GraphQLSchema {
        val generator = SchemaGenerator(schemaConfig)
        return generator.use {
            it.generateSchema(
                queries = queries.orElse(emptyList()).toTopLevelObjects(),
                mutations = mutations.orElse(emptyList()).toTopLevelObjects(),
                subscriptions = subscriptions.orElse(emptyList()).toTopLevelObjects()
            )
        }
    }

    /**
     * This merges the springSchema and the neo4jSchema
     */
    @Bean
    @Primary
    open fun mergedSchema(neo4jSchema: GraphQLSchema, springSchema: GraphQLSchema): GraphQLSchema {
        val builder: GraphQLSchema.Builder = GraphQLSchema.newSchema(neo4jSchema)

        val springCodeRegistry = springSchema.codeRegistry
        builder.codeRegistry(neo4jSchema.codeRegistry.transform { crBuilder ->
            crBuilder.dataFetchers(springCodeRegistry)
            crBuilder.typeResolvers(springCodeRegistry)
        })

        val allTypes = (neo4jSchema.typeMap + springSchema.typeMap).toMutableMap()
        allTypes.replace("Query", mergeType(neo4jSchema.queryType, springSchema.queryType))
        allTypes.replace("Mutation", mergeType(neo4jSchema.mutationType, springSchema.mutationType))

        allTypes["Query"]?.let { builder.query(it as GraphQLObjectType) }
        allTypes["Mutation"]?.let { builder.mutation(it as GraphQLObjectType) }
        allTypes["Subscription"]?.let { builder.subscription(it as GraphQLObjectType) }

        builder.clearAdditionalTypes()
        allTypes.values.forEach { builder.additionalType(it) }

        val schema = builder.build()

        logger.info("\n${schema.print()}")

        return schema
    }

    private fun mergeType(t1: GraphQLObjectType?, t2: GraphQLObjectType?): GraphQLObjectType? {
        if (t1 == null) {
            return t2
        }
        if (t2 == null) {
            return t1
        }
        val builder = GraphQLObjectType.newObject(t1)
        t2.fieldDefinitions.forEach { builder.field(it) }
        return builder.build()
    }

    // This was copied over from {@link com.expediagroup.graphql.spring.extensions.generatorExtensions.kt }
    private fun List<Any>.toTopLevelObjects() = this.map {
        val clazz = if (AopUtils.isAopProxy(it) && it is Advised) {
            it.targetSource.target!!::class
        } else {
            it::class
        }
        TopLevelObject(it, clazz)
    }
}
