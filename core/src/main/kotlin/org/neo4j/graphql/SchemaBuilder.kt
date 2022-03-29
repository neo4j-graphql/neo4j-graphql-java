package org.neo4j.graphql

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
 * A class for augmenting a type definition registry and generate the corresponding data fetcher.
 * There are factory methods, that can be used to simplify augmenting a schema.
 *
 *
 * Generating the schema is done by invoking the following methods:
 * 1. [augmentTypes]
 * 2. [registerScalars]
 * 3. [registerTypeNameResolver]
 * 4. [registerDataFetcher]
 *
 * Each of these steps can be called manually to enhance an existing [TypeDefinitionRegistry]
 */
class SchemaBuilder(
        val typeDefinitionRegistry: TypeDefinitionRegistry,
        val schemaConfig: SchemaConfig = SchemaConfig()
) {

    companion object {
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

            val builder = RuntimeWiring.newRuntimeWiring()
            val codeRegistryBuilder = GraphQLCodeRegistry.newCodeRegistry()
            val schemaBuilder = SchemaBuilder(typeDefinitionRegistry, config)
            schemaBuilder.augmentTypes()
            schemaBuilder.registerScalars(builder)
            schemaBuilder.registerTypeNameResolver(builder)
            schemaBuilder.registerDataFetcher(codeRegistryBuilder, dataFetchingInterceptor)

            return SchemaGenerator().makeExecutableSchema(
                    typeDefinitionRegistry,
                    builder.codeRegistry(codeRegistryBuilder).build()
            )
        }
    }

    private val handler: List<AugmentationHandler>
    private val neo4jTypeDefinitionRegistry: TypeDefinitionRegistry

    init {
        neo4jTypeDefinitionRegistry = getNeo4jEnhancements()
        ensureRootQueryTypeExists(typeDefinitionRegistry)
        handler = mutableListOf(
                CypherDirectiveHandler.Factory(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry),
                AugmentFieldHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry)
        )
        if (schemaConfig.query.enabled) {
            handler.add(QueryHandler.Factory(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry))
        }
        if (schemaConfig.mutation.enabled) {
            handler += listOf(
                    MergeOrUpdateHandler.Factory(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry),
                    DeleteHandler.Factory(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry),
                    CreateTypeHandler.Factory(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry),
                    DeleteRelationHandler.Factory(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry),
                    CreateRelationTypeHandler.Factory(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry),
                    CreateRelationHandler.Factory(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry)
            )
        }
    }


    /**
     * Generated additionally query and mutation fields according to the types present in the [typeDefinitionRegistry].
     * This method will also augment relation fields, so filtering and sorting is available for them
     */
    fun augmentTypes() {
        val queryTypeName = typeDefinitionRegistry.queryTypeName()
        val mutationTypeName = typeDefinitionRegistry.mutationTypeName()
        val subscriptionTypeName = typeDefinitionRegistry.subscriptionTypeName()

        typeDefinitionRegistry.types().values
            .filterIsInstance<ImplementingTypeDefinition<*>>()
            .filter { it.name != queryTypeName && it.name != mutationTypeName && it.name != subscriptionTypeName }
            .forEach { type -> handler.forEach { h -> h.augmentType(type) } }

        // in a second run we enhance all the root fields
        typeDefinitionRegistry.types().values
            .filterIsInstance<ImplementingTypeDefinition<*>>()
            .filter { it.name == queryTypeName || it.name == mutationTypeName || it.name == subscriptionTypeName }
            .forEach { type -> handler.forEach { h -> h.augmentType(type) } }

        val types = mutableListOf<Type<*>>()
        neo4jTypeDefinitionRegistry.directiveDefinitions.values
            .filterNot { typeDefinitionRegistry.getDirectiveDefinition(it.name).isPresent }
            .forEach { directiveDefinition ->
                typeDefinitionRegistry.add(directiveDefinition)
                directiveDefinition.inputValueDefinitions.forEach { types.add(it.type) }
            }
        typeDefinitionRegistry.types()
            .values
            .flatMap { typeDefinition ->
                when (typeDefinition) {
                    is ImplementingTypeDefinition -> typeDefinition.fieldDefinitions
                        .flatMap { fieldDefinition -> fieldDefinition.inputValueDefinitions.map { it.type } + fieldDefinition.type }
                    is InputObjectTypeDefinition -> typeDefinition.inputValueDefinitions.map { it.type }
                    else -> emptyList()
                }
            }
            .forEach { types.add(it) }
        types
            .map { TypeName(it.name()) }
            .filterNot { typeDefinitionRegistry.hasType(it) }
            .mapNotNull { neo4jTypeDefinitionRegistry.getType(it).unwrap() }
            .forEach { typeDefinitionRegistry.add(it) }
    }

    /**
     * Register scalars of this library in the [RuntimeWiring][@param builder]
     * @param builder a builder to create a runtime wiring
     */
    fun registerScalars(builder: RuntimeWiring.Builder) {
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
    }

    /**
     * Register type name resolver in the [RuntimeWiring][@param builder]
     * @param builder a builder to create a runtime wiring
     */
    fun registerTypeNameResolver(builder: RuntimeWiring.Builder) {
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
    }

    /**
     * Register data fetcher in a [GraphQLCodeRegistry][@param codeRegistryBuilder].
     * The data fetcher of this library generate a cypher query and if provided use the dataFetchingInterceptor to run this cypher against a neo4j db.
     * @param codeRegistryBuilder a builder to create a code registry
     * @param dataFetchingInterceptor a function to convert a cypher string into an object by calling the neo4j db
     */
    @JvmOverloads
    fun registerDataFetcher(
            codeRegistryBuilder: GraphQLCodeRegistry.Builder,
            dataFetchingInterceptor: DataFetchingInterceptor?,
            typeDefinitionRegistry: TypeDefinitionRegistry = this.typeDefinitionRegistry
    ) {
        if (dataFetchingInterceptor != null) {
            codeRegistryBuilder.defaultDataFetcher { AliasPropertyDataFetcher() }
        }
        addDataFetcher(typeDefinitionRegistry.queryTypeName(), OperationType.QUERY, dataFetchingInterceptor, codeRegistryBuilder)
        addDataFetcher(typeDefinitionRegistry.mutationTypeName(), OperationType.MUTATION, dataFetchingInterceptor, codeRegistryBuilder)
    }

    private fun addDataFetcher(
            parentType: String,
            operationType: OperationType,
            dataFetchingInterceptor: DataFetchingInterceptor?,
            codeRegistryBuilder: GraphQLCodeRegistry.Builder) {
        typeDefinitionRegistry.getType(parentType)?.unwrap()
            ?.let { it as? ObjectTypeDefinition }
            ?.fieldDefinitions
            ?.filterNot { it.isIgnored() }
            ?.forEach { field ->
                handler.forEach { h ->
                    h.createDataFetcher(operationType, field)?.let { dataFetcher ->
                        val interceptedDataFetcher: DataFetcher<*> = dataFetchingInterceptor?.let {
                            DataFetcher { env -> dataFetchingInterceptor.fetchData(env, dataFetcher) }
                        } ?: dataFetcher
                        codeRegistryBuilder.dataFetcher(FieldCoordinates.coordinates(parentType, field.name), interceptedDataFetcher)
                    }
                }
            }
    }

    private fun ensureRootQueryTypeExists(enhancedRegistry: TypeDefinitionRegistry) {
        var schemaDefinition = enhancedRegistry.schemaDefinition().orElse(null)
        if (schemaDefinition?.operationTypeDefinitions?.find { it.name == "query" } != null) {
            return
        }

        enhancedRegistry.add(ObjectTypeDefinition.newObjectTypeDefinition().name("Query").build())

        if (schemaDefinition != null) {
            // otherwise, adding a transform schema would fail
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

    private fun getNeo4jEnhancements(): TypeDefinitionRegistry {
        val directivesSdl = javaClass.getResource("/neo4j_types.graphql")?.readText() +
                javaClass.getResource("/lib_directives.graphql")?.readText()
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

    class AliasPropertyDataFetcher : DataFetcher<Any> {
        override fun get(env: DataFetchingEnvironment): Any? {
            val source = env.getSource<Any>() ?: return null
            val propertyName = env.mergedField.singleField.alias ?: env.mergedField.singleField.name
            return PropertyDataFetcherHelper.getPropertyValue(propertyName, source, env.fieldType, env)
        }
    }
}
