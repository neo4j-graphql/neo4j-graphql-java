package org.neo4j.graphql

import graphql.language.*
import graphql.schema.*
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Model
import org.neo4j.graphql.domain.directives.Annotations.Companion.LIBRARY_DIRECTIVES
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.driver.adapter.Neo4jAdapter
import org.neo4j.graphql.handler.ConnectionResolver
import org.neo4j.graphql.handler.ImplementingTypeConnectionFieldResolver
import org.neo4j.graphql.handler.ReadResolver
import org.neo4j.graphql.scalars.BigIntScalar
import org.neo4j.graphql.scalars.DurationScalar
import org.neo4j.graphql.scalars.TemporalScalar
import org.neo4j.graphql.schema.AugmentationContext
import org.neo4j.graphql.schema.AugmentationHandler
import org.neo4j.graphql.schema.model.outputs.InterfaceSelection
import org.neo4j.graphql.schema.model.outputs.NodeSelection

/**
 * A class for augmenting a type definition registry and generate the corresponding data fetcher.
 * There are factory methods, that can be used to simplify augmenting a schema.
 *
 *
 * Generating the schema is done by invoking the following methods:
 * 1. [augmentTypes]
 * 2. [registerScalars]
 * 3. [registerTypeNameResolver]
 * 4. [registerNeo4jAdapter]
 *
 * Each of these steps can be called manually to enhance an existing [TypeDefinitionRegistry]
 */
class SchemaBuilder @JvmOverloads constructor(
    val typeDefinitionRegistry: TypeDefinitionRegistry,
    val schemaConfig: SchemaConfig = SchemaConfig(),
) {

    companion object {

        @JvmStatic
        @JvmOverloads
        fun fromSchema(sdl: String, config: SchemaConfig = SchemaConfig()): SchemaBuilder {
            val schemaParser = SchemaParser()
            val typeDefinitionRegistry = schemaParser.parse(sdl)
            return SchemaBuilder(typeDefinitionRegistry, config)
        }

        /**
         * @param sdl the schema to augment
         * @param neo4jAdapter the adapter to run the generated cypher queries
         * @param config defines how the schema should get augmented
         */
        @JvmStatic
        @JvmOverloads
        fun buildSchema(
            sdl: String,
            config: SchemaConfig = SchemaConfig(),
            neo4jAdapter: Neo4jAdapter = Neo4jAdapter.NO_OP,
            addLibraryDirectivesToSchema: Boolean = true,
        ): GraphQLSchema = fromSchema(sdl, config)
            .withNeo4jAdapter(neo4jAdapter)
            .addLibraryDirectivesToSchema(addLibraryDirectivesToSchema)
            .build()
    }

    private val handler: List<AugmentationHandler>
    private val neo4jTypeDefinitionRegistry: TypeDefinitionRegistry = getNeo4jEnhancements()
    private val augmentedFields = mutableListOf<AugmentationHandler.AugmentedField>()
    private val ctx = AugmentationContext(schemaConfig, typeDefinitionRegistry)
    private var addLibraryDirectivesToSchema: Boolean = false;
    private var codeRegistryBuilder: GraphQLCodeRegistry.Builder? = null
    private var runtimeWiringBuilder: RuntimeWiring.Builder? = null
    private var neo4jAdapter: Neo4jAdapter = Neo4jAdapter.NO_OP

    init {
        handler = mutableListOf(
            ReadResolver.Factory(ctx),
            ConnectionResolver.Factory(ctx),
            ImplementingTypeConnectionFieldResolver.Factory(ctx)
        )
    }

    fun addLibraryDirectivesToSchema(addLibraryDirectivesToSchema: Boolean): SchemaBuilder {
        this.addLibraryDirectivesToSchema = addLibraryDirectivesToSchema
        return this
    }

    fun withCodeRegistryBuilder(codeRegistryBuilder: GraphQLCodeRegistry.Builder): SchemaBuilder {
        this.codeRegistryBuilder = codeRegistryBuilder
        return this
    }

    fun withRuntimeWiringBuilder(runtimeWiring: RuntimeWiring.Builder): SchemaBuilder {
        this.runtimeWiringBuilder = runtimeWiring
        return this
    }

    fun withNeo4jAdapter(neo4jAdapter: Neo4jAdapter): SchemaBuilder {
        this.neo4jAdapter = neo4jAdapter
        return this
    }

    fun build(): GraphQLSchema {
        augmentTypes(addLibraryDirectivesToSchema)
        val runtimeWiringBuilder = this.runtimeWiringBuilder ?: RuntimeWiring.newRuntimeWiring()
        registerScalars(runtimeWiringBuilder)
        registerTypeNameResolver(runtimeWiringBuilder)

        val codeRegistryBuilder = this.codeRegistryBuilder ?: GraphQLCodeRegistry.newCodeRegistry()
        registerNeo4jAdapter(codeRegistryBuilder, neo4jAdapter)

        return SchemaGenerator().makeExecutableSchema(
            typeDefinitionRegistry,
            runtimeWiringBuilder.codeRegistry(codeRegistryBuilder).build()
        )

    }


    /**
     * Generated additionally query and mutation fields according to the types present in the [typeDefinitionRegistry].
     * This method will also augment relation fields, so filtering and sorting is available for them
     *
     * @param addLibraryDirectivesToSchema if set to true, the library directives will be added to the schema. Default: true
     */
    @JvmOverloads
    fun augmentTypes(addLibraryDirectivesToSchema: Boolean = true) {
        val model = Model.createModel(typeDefinitionRegistry, schemaConfig)

        // remove type definition for node since it will be added while augmenting the schema
        model.nodes
            .mapNotNull { typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(it.name) }
            .forEach { typeDefinitionRegistry.remove(it) }

        model.interfaces
            .mapNotNull { typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it.name) }
            .forEach { typeDefinitionRegistry.remove(it) }

        model.relationship
            .mapNotNull { it.properties?.typeName }
            .mapNotNull { typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(it) }
            .forEach { typeDefinitionRegistry.remove(it) }

        augmentedFields += handler.filterIsInstance<AugmentationHandler.NodeAugmentation>()
            .flatMap { h -> model.nodes.flatMap(h::augmentNode) }
        augmentedFields += handler.filterIsInstance<AugmentationHandler.EntityAugmentation>()
            .flatMap { h -> model.entities.flatMap(h::augmentEntity) }
        augmentedFields += handler.filterIsInstance<AugmentationHandler.ModelAugmentation>()
            .flatMap { h -> h.augmentModel(model) }

        removeLibraryDirectivesFromInterfaces()
        removeLibraryDirectivesFromUnions()
        removeLibraryDirectivesFromRootTypes()
        removeLibraryDirectivesFromSchemaTypes()

        ensureAllReferencedNodesExists(model)
        ensureAllReferencedInterfacesExists(model)
        ensureReferencedLibraryTypesExists(addLibraryDirectivesToSchema)
        ensureRootQueryTypeExists()
    }

    private fun ensureReferencedLibraryTypesExists(addLibraryDirectivesToSchema: Boolean) {
        val types = mutableListOf<Type<*>>()
        if (addLibraryDirectivesToSchema) {
            neo4jTypeDefinitionRegistry.directiveDefinitions.values
                .filterNot { typeDefinitionRegistry.getDirectiveDefinition(it.name).isPresent }
                .forEach { directiveDefinition ->
                    typeDefinitionRegistry.add(directiveDefinition)
                    directiveDefinition.inputValueDefinitions.forEach { types.add(it.type) }
                }
        }
        typeDefinitionRegistry.types()
            .values
            .flatMap { typeDefinition ->
                when (typeDefinition) {
                    is ImplementingTypeDefinition -> typeDefinition.fieldDefinitions
                        .flatMap { fieldDefinition -> fieldDefinition.inputValueDefinitions.map { it.type } + fieldDefinition.type } +
                            typeDefinition.implements

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

    private fun ensureAllReferencedInterfacesExists(model: Model) {
        model.nodes
            .flatMap { node ->
                node.interfaces +
                        node.fields.filterIsInstance<RelationField>()
                            .map { it.target }
                            .filterIsInstance<Interface>() +
                        node.interfaces.flatMap { it.interfaces }
            }
            .distinctBy { it.name }
            .forEach { InterfaceSelection.Augmentation.generateInterfaceSelection(it, ctx) }
    }

    private fun removeLibraryDirectivesFromInterfaces() {
        typeDefinitionRegistry.getTypes(InterfaceTypeDefinition::class.java).forEach { interfaceTypeDefinition ->
            typeDefinitionRegistry.replace(interfaceTypeDefinition.transform { builder ->
                builder.addNonLibDirectives(interfaceTypeDefinition)
                builder.definitions(interfaceTypeDefinition.fieldDefinitions.map { field ->
                    field.transform { fieldBuilder -> fieldBuilder.addNonLibDirectives(field) }
                })
            })
        }
    }

    private fun removeLibraryDirectivesFromUnions() {
        typeDefinitionRegistry.getTypes(UnionTypeDefinition::class.java).forEach { unionTypeDefinition ->
            typeDefinitionRegistry.replace(unionTypeDefinition.transform { builder ->
                builder.addNonLibDirectives(unionTypeDefinition)
            })
        }
    }

    private fun removeLibraryDirectivesFromRootTypes() {
        listOf(
            typeDefinitionRegistry.queryType(),
            typeDefinitionRegistry.mutationType(),
            typeDefinitionRegistry.subscriptionType(),
        )
            .filterNotNull()
            .forEach { obj ->
                typeDefinitionRegistry.replace(obj.transform { objBuilder ->
                    objBuilder.addNonLibDirectives(obj)
                    objBuilder.fieldDefinitions(obj.fieldDefinitions.map { field ->
                        field.transform { fieldBuilder -> fieldBuilder.addNonLibDirectives(field) }
                    })
                })
            }
    }

    private fun removeLibraryDirectivesFromSchemaTypes() {
        typeDefinitionRegistry.schemaExtensionDefinitions.forEach { obj ->
            typeDefinitionRegistry.remove(obj)
            typeDefinitionRegistry.add(obj.transformExtension { objBuilder ->
                objBuilder.directives(obj.directives.filterNot { LIBRARY_DIRECTIVES.contains(it.name) })
            })
        }
        typeDefinitionRegistry.schemaDefinition()?.unwrap()?.let { obj ->
            typeDefinitionRegistry.replace(obj.transform { objBuilder ->
                objBuilder.directives(obj.directives.filterNot { LIBRARY_DIRECTIVES.contains(it.name) })

            })
        }
    }

    private fun ensureAllReferencedNodesExists(model: Model) {
        val nodesByName = model.nodes.associateBy { it.name }

        var typesToCheck = typeDefinitionRegistry.getTypes(ImplementingTypeDefinition::class.java)
            .map { it.name }
            .toSet()
        while (typesToCheck.isNotEmpty()) {
            typesToCheck = typesToCheck
                .mapNotNull { typeDefinitionRegistry.getUnwrappedType(it) }
                .filterIsInstance<ImplementingTypeDefinition<*>>()
                .flatMap { it.fieldDefinitions }
                .asSequence()
                .map { it.type.name() }
                .filter { typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(it) == null }
                .mapNotNull { nodesByName[it] }
                .onEach { NodeSelection.Augmentation.generateNodeSelection(it, ctx) }
                .map { it.name }
                .toSet()
        }
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
                    Constants.BIG_INT -> BigIntScalar.INSTANCE
                    Constants.DATE -> TemporalScalar.DATE
                    Constants.TIME -> TemporalScalar.TIME
                    Constants.LOCAL_TIME -> TemporalScalar.LOCAL_TIME
                    Constants.DATE_TIME -> TemporalScalar.DATE_TIME
                    Constants.LOCAL_DATE_TIME -> TemporalScalar.LOCAL_DATE_TIME
                    Constants.DURATION -> DurationScalar.INSTANCE
                    else -> GraphQLScalarType.newScalar()
                        .name(name)
                        .description(
                            definition.description?.getContent()
                                ?: definition.comments?.joinToString("\n") { it.getContent() }
                                    ?.takeIf { it.isNotBlank() }
                        )
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
        (typeDefinitionRegistry.getTypes(InterfaceTypeDefinition::class.java)
                + typeDefinitionRegistry.getTypes(UnionTypeDefinition::class.java))
            .forEach { typeDefinition ->
                builder.type(typeDefinition.name) {
                    it.typeResolver { env ->
                        (env.getObject() as? Map<String, Any>)
                            ?.let { data -> data[Constants.TYPE_NAME] as? String }
                            ?.let { typeName -> env.schema.getObjectType(typeName) }
                    }
                }
            }
    }

    /**
     * Register data fetcher in the [GraphQLCodeRegistry.Builder][@param codeRegistryBuilder]
     * @param codeRegistryBuilder a builder to create a code registry
     * @param neo4jAdapter the adapter to run the generated cypher queries
     */
    fun registerNeo4jAdapter(
        codeRegistryBuilder: GraphQLCodeRegistry.Builder,
        neo4jAdapter: Neo4jAdapter,
    ) {
        codeRegistryBuilder.defaultDataFetcher { AliasPropertyDataFetcher() }
        augmentedFields.forEach { (coordinates, dataFetcher) ->
            codeRegistryBuilder.dataFetcher(coordinates, DataFetcher { env ->
                env.graphQlContext.put(Neo4jAdapter.CONTEXT_KEY, neo4jAdapter)
                dataFetcher.get(env)
            })
        }
    }

    private fun ensureRootQueryTypeExists() {
        if (typeDefinitionRegistry.queryType() != null) {
            return
        }
        typeDefinitionRegistry.add(
            ObjectTypeDefinition.newObjectTypeDefinition().name("Query")
                .fieldDefinition(
                    FieldDefinition
                        .newFieldDefinition()
                        .name("_empty")
                        .type(Constants.Types.Boolean)
                        .build()
                ).build()
        )
    }

    private fun getNeo4jEnhancements(): TypeDefinitionRegistry {
        val directivesSdl = javaClass.getResource("/neo4j_types.graphql")?.readText() +
                javaClass.getResource("/lib_directives.graphql")?.readText()
        val typeDefinitionRegistry = SchemaParser().parse(directivesSdl)
        return typeDefinitionRegistry
    }

    class AliasPropertyDataFetcher : DataFetcher<Any> {
        override fun get(env: DataFetchingEnvironment): Any? {
            val source = env.getSource<Any>() ?: return null
            val propertyName = env.mergedField.singleField.alias ?: env.mergedField.singleField.name
            return PropertyDataFetcherHelper.getPropertyValue(propertyName, source, env.fieldType, { env })
        }
    }
}
