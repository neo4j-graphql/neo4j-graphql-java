package org.neo4j.graphql

import graphql.Scalars
import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.handler.*
import org.neo4j.graphql.handler.projection.ProjectionBase
import org.neo4j.graphql.handler.relation.CreateRelationHandler
import org.neo4j.graphql.handler.relation.CreateRelationTypeHandler
import org.neo4j.graphql.handler.relation.DeleteRelationHandler

object SchemaBuilder {
    private const val MUTATION = "Mutation"
    private const val SUBSCRIPTION = "Subscription"
    private const val QUERY = "Query"

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
        mergeNeo4jEnhancements(typeDefinitionRegistry)
        if (!typeDefinitionRegistry.getType(QUERY).isPresent) {
            typeDefinitionRegistry.add(ObjectTypeDefinition.newObjectTypeDefinition().name(QUERY).build())
        }
        val builder = RuntimeWiring.newRuntimeWiring()
            .scalar(DynamicProperties.INSTANCE)
        typeDefinitionRegistry
            .getTypes(InterfaceTypeDefinition::class.java)
            .forEach { typeDefinition ->
                builder.type(typeDefinition.name) {
                    it.typeResolver { env ->
                        (env.getObject() as? Map<String, Any>)
                            ?.let { data -> data[ProjectionBase.TYPE_NAME] as? String }
                            ?.let { typeName -> env.schema.getObjectType(typeName) }
                    }
                }
            }
        val sourceSchema = SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, builder.build())

        val handler = getHandler(config)

        var targetSchema = augmentSchema(sourceSchema, handler)
        targetSchema = addDataFetcher(targetSchema, dataFetchingInterceptor, handler)
        return targetSchema
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

    private fun augmentSchema(sourceSchema: GraphQLSchema, handler: List<AugmentationHandler>): GraphQLSchema {
        val types = sourceSchema.typeMap.toMutableMap()
        val env = BuildingEnv(types)

        types.values
            .filterIsInstance<GraphQLFieldsContainer>()
            .filter {
                !it.name.startsWith("__")
                        && !it.isNeo4jType()
                        && it.name != QUERY
                        && it.name != MUTATION
                        && it.name != SUBSCRIPTION
            }
            .forEach { type ->
                handler.forEach { h -> h.augmentType(type, env) }
            }

        types.replaceAll { _, sourceType ->
            when {
                sourceType.name.startsWith("__") -> sourceType
                sourceType is GraphQLObjectType -> sourceType.transform { builder ->
                    builder.clearFields().clearInterfaces()
                    // to prevent duplicated types in schema
                    sourceType.interfaces.forEach { builder.withInterface(GraphQLTypeReference(it.name)) }
                    sourceType.fieldDefinitions.forEach { f -> builder.field(enhanceRelations(f)) }
                }
                sourceType is GraphQLInterfaceType -> sourceType.transform { builder ->
                    builder.clearFields()
                    sourceType.fieldDefinitions.forEach { f -> builder.field(enhanceRelations(f)) }
                }
                else -> sourceType
            }
        }

        return GraphQLSchema
            .newSchema(sourceSchema)
            .clearAdditionalTypes()
            .query(types[QUERY] as? GraphQLObjectType)
            .mutation(types[MUTATION] as? GraphQLObjectType)
            .additionalTypes(types.values.toSet())
            .build()
    }

    private fun enhanceRelations(fd: GraphQLFieldDefinition): GraphQLFieldDefinition {
        return fd.transform {
            // to prevent duplicated types in schema
            it.type(fd.type.ref() as GraphQLOutputType)

            if (!fd.isRelationship() || !fd.type.isList()) {
                return@transform
            }

            if (fd.getArgument(ProjectionBase.FIRST) == null) {
                it.argument { a -> a.name(ProjectionBase.FIRST).type(Scalars.GraphQLInt) }
            }
            if (fd.getArgument(ProjectionBase.OFFSET) == null) {
                it.argument { a -> a.name(ProjectionBase.OFFSET).type(Scalars.GraphQLInt) }
            }
            // TODO implement ordering
//                if (fd.getArgument(ProjectionBase.ORDER_BY) == null) {
//                    val typeName = fd.type.name()!!
//                    val orderingType = addOrdering(typeName, metaProvider.getNodeType(typeName)!!.fieldDefinitions().filter { it.type.isScalar() })
//                    it.argument { a -> a.name(ProjectionBase.ORDER_BY).type(orderingType) }
//                }
        }
    }

    private fun addDataFetcher(sourceSchema: GraphQLSchema, dataFetchingInterceptor: DataFetchingInterceptor?, handler: List<AugmentationHandler>): GraphQLSchema {
        val codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry(sourceSchema.codeRegistry)
        addDataFetcher(sourceSchema.queryType, dataFetchingInterceptor, handler, codeRegistryBuilder)
        addDataFetcher(sourceSchema.mutationType, dataFetchingInterceptor, handler, codeRegistryBuilder)
        return sourceSchema.transform { it.codeRegistry(codeRegistryBuilder.build()) }
    }

    private fun addDataFetcher(
            rootType: GraphQLObjectType?,
            dataFetchingInterceptor: DataFetchingInterceptor?,
            handler: List<AugmentationHandler>,
            codeRegistryBuilder: GraphQLCodeRegistry.Builder) {
        if (rootType == null) return
        rootType.fieldDefinitions.forEach { field ->
            handler.forEach { h ->
                h.createDataFetcher(rootType, field)?.let { dataFetcher ->
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

    private fun mergeNeo4jEnhancements(typeDefinitionRegistry: TypeDefinitionRegistry) {
        val directivesSdl = javaClass.getResource("/neo4j.graphql").readText()
        typeDefinitionRegistry.merge(SchemaParser().parse(directivesSdl))
        neo4jTypeDefinitions
            .forEach {
                val type = typeDefinitionRegistry.getType(it.typeDefinition)
                    .orElseThrow { IllegalStateException("type ${it.typeDefinition} not found") }
                        as ObjectTypeDefinition
                addInputType(typeDefinitionRegistry, it.inputDefinition, type.fieldDefinitions)
            }
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

    @JvmStatic
    fun mergeNeo4jEnhancements(builder: GraphQLSchema.Builder) {
        val schemaParser = SchemaParser()
        val directivesSdl = javaClass.getResource("/neo4j.graphql").readText()
        val typeDefinitionRegistry = schemaParser.parse(directivesSdl)
        val wiring = RuntimeWiring.newRuntimeWiring().scalar(DynamicProperties.INSTANCE).build()
        val schema = SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, wiring)
        schema.directives.forEach { builder.additionalDirective(it) }
        schema.additionalTypes.forEach { builder.additionalType(it) }
        neo4jTypeDefinitions
            .forEach {
                val type = (schema.getType(it.typeDefinition) as? GraphQLObjectType)
                        ?: throw IllegalStateException("type ${it.typeDefinition} not found")

                builder.additionalType(getInputType(it.inputDefinition, type.fieldDefinitions))
            }
    }

    private fun getInputType(inputName: String, relevantFields: List<GraphQLFieldDefinition>): GraphQLInputObjectType {
        return GraphQLInputObjectType.newInputObject()
            .name(inputName)
            .fields(getInputValueDefinitions(relevantFields))
            .build()
    }

    private fun getInputValueDefinitions(relevantFields: List<GraphQLFieldDefinition>): List<GraphQLInputObjectField> {
        return relevantFields.map {
            val type = (it.type as? GraphQLNonNull)?.wrappedType ?: it.type
            GraphQLInputObjectField
                .newInputObjectField()
                .name(it.name)
                .type(type as? GraphQLInputType
                        ?: throw IllegalArgumentException("${type.name} is not allowed for input"))
                .build()
        }
    }
}