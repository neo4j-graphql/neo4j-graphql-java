package org.neo4j.graphql

import graphql.Scalars
import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.AugmentationHandler.OperationType
import org.neo4j.graphql.handler.*
import org.neo4j.graphql.handler.projection.ProjectionBase
import org.neo4j.graphql.handler.relation.CreateRelationHandler
import org.neo4j.graphql.handler.relation.CreateRelationTypeHandler
import org.neo4j.graphql.handler.relation.DeleteRelationHandler

/**
 * Contains factory methods to generate an augmented graphql schema
 */
object SchemaBuilder {

    /**
     * @param sdl the schema to augment
     * @param config defines how the schema should get augmented
     * @param dataFetchingInterceptor since this library registers dataFetcher for its augmented methods, these data
     * fetchers may be called by other resolver. This interceptor will let you convert a cypher query into real data.
     */
    @JvmStatic
    @JvmOverloads
    fun buildSchema(sdl: String, config: SchemaConfig = SchemaConfig(), dataFetchingInterceptor: DataFetchingInterceptor? = null): GraphQLSchema {
        val schemaParser = SchemaParser()
        val typeDefinitionRegistry = schemaParser.parse(sdl)
        return buildSchema(typeDefinitionRegistry, config, dataFetchingInterceptor)
    }

    /**
     * @param typeDefinitionRegistry a registry containing all the types, that should be augmented
     * @param config defines how the schema should get augmented
     * @param dataFetchingInterceptor since this library registers dataFetcher for its augmented methods, these data
     * fetchers may be called by other resolver. This interceptor will let you convert a cypher query into real data.
     */
    @JvmStatic
    @JvmOverloads
    fun buildSchema(typeDefinitionRegistry: TypeDefinitionRegistry, config: SchemaConfig = SchemaConfig(), dataFetchingInterceptor: DataFetchingInterceptor? = null): GraphQLSchema {
        val enhancedRegistry = typeDefinitionRegistry.merge(getNeo4jEnhancements())
        ensureRootQueryTypeExists(enhancedRegistry)

        val builder = RuntimeWiring.newRuntimeWiring()
        typeDefinitionRegistry.scalars()
            .filterNot { entry -> GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.containsKey(entry.key) }
            .forEach { (name, definition) ->
                val scalar = when (name) {
                    "DynamicProperties" -> DynamicProperties.INSTANCE
                    else -> GraphQLScalarType.newScalar()
                        .name(name)
                        .description(definition.description?.getContent() ?: "Scalar $name")
                        .withDirectives(*definition.directives.filterIsInstance<GraphQLDirective>().toTypedArray())
                        .definition(definition)
                        .coercing(NoOpCoercing)
                        .build()
                }
                builder.scalar(scalar)
            }


        enhancedRegistry
            .getTypes(InterfaceTypeDefinition::class.java)
            .forEach { typeDefinition ->
                builder.type(typeDefinition.name) {
                    it.typeResolver { env ->
                        (env.getObject() as? Map<String, Any>)
                            ?.let { data -> data.get(ProjectionBase.TYPE_NAME) as? String }
                            ?.let { typeName -> env.schema.getObjectType(typeName) }
                    }
                }
            }
        val sourceSchema = SchemaGenerator().makeExecutableSchema(enhancedRegistry, builder.build())

        val handler = getHandler(config)

        var targetSchema = augmentSchema(sourceSchema, handler, config)
        targetSchema = addDataFetcher(targetSchema, dataFetchingInterceptor, handler)
        return targetSchema
    }

    private fun ensureRootQueryTypeExists(enhancedRegistry: TypeDefinitionRegistry) {
        var schemaDefinition = enhancedRegistry.schemaDefinition().orElse(null)
        if (schemaDefinition?.operationTypeDefinitions?.find { it.name == "query" } != null) {
            return
        }

        enhancedRegistry.add(ObjectTypeDefinition.newObjectTypeDefinition().name("Query").build())

        if (schemaDefinition != null) {
            // otherwise adding a transform schema would fail
            enhancedRegistry.remove(schemaDefinition)
        } else {
            schemaDefinition = SchemaDefinition.newSchemaDefinition().build()
        }

        enhancedRegistry.add(schemaDefinition.transform {
            it.operationTypeDefinition(OperationTypeDefinition
                .newOperationTypeDefinition()
                .name("query")
                .typeName(TypeName("Query"))
                .build())
        })
    }

    private fun getHandler(schemaConfig: SchemaConfig): List<AugmentationHandler> {
        val handler = mutableListOf<AugmentationHandler>(
                CypherDirectiveHandler.Factory(schemaConfig)
        )
        if (schemaConfig.query.enabled) {
            handler.add(QueryHandler.Factory(schemaConfig))
        }
        if (schemaConfig.mutation.enabled) {
            handler += listOf(
                    MergeOrUpdateHandler.Factory(schemaConfig),
                    DeleteHandler.Factory(schemaConfig),
                    CreateTypeHandler.Factory(schemaConfig),
                    DeleteRelationHandler.Factory(schemaConfig),
                    CreateRelationTypeHandler.Factory(schemaConfig),
                    CreateRelationHandler.Factory(schemaConfig)
            )
        }
        return handler
    }

    private fun augmentSchema(sourceSchema: GraphQLSchema, handler: List<AugmentationHandler>, schemaConfig: SchemaConfig): GraphQLSchema {
        val types = sourceSchema.typeMap.toMutableMap()
        val env = BuildingEnv(types, sourceSchema)
        val queryTypeName = sourceSchema.queryTypeName()
        val mutationTypeName = sourceSchema.mutationTypeName()
        val subscriptionTypeName = sourceSchema.subscriptionTypeName()
        types.values
            .filterIsInstance<GraphQLFieldsContainer>()
            .filter {
                !it.name.startsWith("__")
                        && !it.isNeo4jType()
                        && it.name != queryTypeName
                        && it.name != mutationTypeName
                        && it.name != subscriptionTypeName
            }
            .forEach { type ->
                handler.forEach { h -> h.augmentType(type, env) }
            }

        // since new types my be added to `types` we copy the map, to safely modify the entries and later add these
        // modified entries back to the `types`
        val adjustedTypes = types.toMutableMap()
        adjustedTypes.replaceAll { _, sourceType ->
            when {
                sourceType.name.startsWith("__") -> sourceType
                sourceType is GraphQLObjectType -> sourceType.transform { builder ->
                    builder.clearFields().clearInterfaces()
                    // to prevent duplicated types in schema
                    sourceType.interfaces.forEach { builder.withInterface(GraphQLTypeReference(it.name)) }
                    sourceType.fieldDefinitions.forEach { f -> builder.field(enhanceRelations(f, env, schemaConfig)) }
                }
                sourceType is GraphQLInterfaceType -> sourceType.transform { builder ->
                    builder.clearFields()
                    sourceType.fieldDefinitions.forEach { f -> builder.field(enhanceRelations(f, env, schemaConfig)) }
                }
                else -> sourceType
            }
        }
        types.putAll(adjustedTypes)

        return GraphQLSchema
            .newSchema(sourceSchema)
            .clearAdditionalTypes()
            .query(types[queryTypeName] as? GraphQLObjectType)
            .mutation(types[mutationTypeName] as? GraphQLObjectType)
            .additionalTypes(types.values.toSet())
            .build()
    }

    private fun enhanceRelations(fd: GraphQLFieldDefinition, env: BuildingEnv, schemaConfig: SchemaConfig): GraphQLFieldDefinition {
        return fd.transform { fieldBuilder ->
            // to prevent duplicated types in schema
            fieldBuilder.type(fd.type.ref() as GraphQLOutputType)

            if (!fd.isRelationship() || !fd.type.isList()) {
                return@transform
            }

            if (fd.getArgument(ProjectionBase.FIRST) == null) {
                fieldBuilder.argument { a -> a.name(ProjectionBase.FIRST).type(Scalars.GraphQLInt) }
            }
            if (fd.getArgument(ProjectionBase.OFFSET) == null) {
                fieldBuilder.argument { a -> a.name(ProjectionBase.OFFSET).type(Scalars.GraphQLInt) }
            }

            val fieldType = fd.type.inner() as? GraphQLFieldsContainer ?: return@transform

            if (fd.getArgument(ProjectionBase.ORDER_BY) == null) {
                env.addOrdering(fieldType)?.let { orderingTypeName ->
                    val orderType = GraphQLList(GraphQLNonNull(GraphQLTypeReference(orderingTypeName)))
                    fieldBuilder.argument { a -> a.name(ProjectionBase.ORDER_BY).type(orderType) }

                }
            }

            if (schemaConfig.query.enabled && !schemaConfig.query.exclude.contains(fieldType.name) && fd.getArgument(ProjectionBase.FILTER) == null) {
                val filterTypeName = env.addFilterType(fieldType)
                fieldBuilder.argument(input(ProjectionBase.FILTER, GraphQLTypeReference(filterTypeName)))
            }
        }
    }

    private fun addDataFetcher(sourceSchema: GraphQLSchema, dataFetchingInterceptor: DataFetchingInterceptor?, handler: List<AugmentationHandler>): GraphQLSchema {
        val codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry(sourceSchema.codeRegistry)
        addDataFetcher(sourceSchema.queryType, OperationType.QUERY, dataFetchingInterceptor, handler, codeRegistryBuilder)
        addDataFetcher(sourceSchema.mutationType, OperationType.MUTATION, dataFetchingInterceptor, handler, codeRegistryBuilder)
        return sourceSchema.transform { it.codeRegistry(codeRegistryBuilder.build()) }
    }

    private fun addDataFetcher(
            rootType: GraphQLObjectType?,
            operationType: OperationType,
            dataFetchingInterceptor: DataFetchingInterceptor?,
            handler: List<AugmentationHandler>,
            codeRegistryBuilder: GraphQLCodeRegistry.Builder) {
        if (rootType == null) return
        rootType.fieldDefinitions.forEach { field ->
            handler.forEach { h ->
                h.createDataFetcher(operationType, field)?.let { dataFetcher ->
                    val df: DataFetcher<*> = dataFetchingInterceptor?.let {
                        DataFetcher { env ->
                            dataFetchingInterceptor.fetchData(env, dataFetcher)
                        }
                    } ?: dataFetcher
                    codeRegistryBuilder.dataFetcher(rootType, field, df)
                }
            }
        }
    }

    private fun getNeo4jEnhancements(): TypeDefinitionRegistry {
        val directivesSdl = javaClass.getResource("/neo4j_types.graphql").readText() +
                javaClass.getResource("/lib_directives.graphql").readText()
        val typeDefinitionRegistry = SchemaParser().parse(directivesSdl)
        neo4jTypeDefinitions
            .forEach {
                val type = typeDefinitionRegistry.getType(it.typeDefinition)
                    .orElseThrow { IllegalStateException("type ${it.typeDefinition} not found") }
                        as ObjectTypeDefinition
                addInputType(typeDefinitionRegistry, it.inputDefinition, type.fieldDefinitions)
            }
        return typeDefinitionRegistry
    }

    private fun addInputType(typeDefinitionRegistry: TypeDefinitionRegistry, inputName: String, relevantFields: List<FieldDefinition>): String {
        if (typeDefinitionRegistry.getType(inputName).isPresent) {
            return inputName
        }
        val inputType = InputObjectTypeDefinition.newInputObjectDefinition()
            .name(inputName)
            .inputValueDefinitions(relevantFields.map {
                InputValueDefinition.newInputValueDefinition()
                    .name(it.name)
                    .type(it.type)
                    .build()
            })
            .build()
        typeDefinitionRegistry.add(inputType)
        return inputName
    }
}
