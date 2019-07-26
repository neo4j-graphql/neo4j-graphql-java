package org.neo4j.graphql

import graphql.language.*
import graphql.language.TypeDefinition
import graphql.scalars.`object`.ObjectScalar
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.handler.BaseDataFetcher
import org.neo4j.graphql.handler.CypherDirectiveHandler
import org.neo4j.graphql.handler.QueryHandler

object SchemaBuilder {
    @JvmStatic
    fun buildSchema(sdl: String, ctx: Translator.Context = Translator.Context()): GraphQLSchema {
        val schemaParser = SchemaParser()
        val baseTypeDefinitionRegistry = schemaParser.parse(sdl)
        val builder = RuntimeWiring.newRuntimeWiring()
            .scalar(ObjectScalar())
        val augmentedTypeDefinitionRegistry = augmentSchema(baseTypeDefinitionRegistry, schemaParser, ctx, builder)

        baseTypeDefinitionRegistry
            .getTypes(InterfaceTypeDefinition::class.java)
            .forEach { typeDefinition -> builder.type(typeDefinition.name) { it.typeResolver { null } } }

        val runtimeWiring = builder.build()

        val schemaGenerator = SchemaGenerator()
        return schemaGenerator.makeExecutableSchema(augmentedTypeDefinitionRegistry, runtimeWiring)
            .transform { sc -> sc.build() } // todo add new queries, filters, enums etc.
    }

    private fun augmentSchema(
            typeDefinitionRegistry: TypeDefinitionRegistry,
            schemaParser: SchemaParser,
            ctx: Translator.Context,
            builder: RuntimeWiring.Builder): TypeDefinitionRegistry {
        val directivesSdl = javaClass.getResource("/neo4j_directives.graphql").readText()
        typeDefinitionRegistry.merge(schemaParser.parse(directivesSdl))

        val interfaceTypeDefinitions = typeDefinitionRegistry.types().values.filterIsInstance<InterfaceTypeDefinition>()
        val objectTypeDefinitions = typeDefinitionRegistry.types().values.filterIsInstance<ObjectTypeDefinition>()

        val nodeDefinitions: List<TypeDefinition<*>> = interfaceTypeDefinitions + objectTypeDefinitions

        val metaProvider = TypeRegistryMetaProvider(typeDefinitionRegistry)

        val nodeMutations = nodeDefinitions.map { createNodeMutation(ctx, it.getNodeType()!!, metaProvider) }
        val relationTypes = objectTypeDefinitions
            .filter { it.getDirective(DirectiveConstants.RELATION) != null }
            .map { it.getDirective(DirectiveConstants.RELATION).getArgument(DirectiveConstants.RELATION_NAME).value.toJavaValue().toString() to it }
            .toMap()

        val relMutations = objectTypeDefinitions.flatMap { source ->
            createRelationshipMutations(source, objectTypeDefinitions, relationTypes, metaProvider, ctx)
        } + relationTypes.values.map { createNodeMutation(ctx, it.getNodeType()!!, metaProvider) }

        val augmentations = nodeMutations + relMutations

        augmentations
            .flatMap { listOf(it.filterType, it.ordering, it.inputType) }
            .filterNotNull()
            .forEach { typeDefinitionRegistry.add(it as SDLDefinition<*>) }

        val schemaDefinition = typeDefinitionRegistry
            .schemaDefinition()
            .orElseGet { SchemaDefinition.newSchemaDefinition().build() }

        var queryDefinition = getOrCreateObjectTypeDefinition("Query", schemaDefinition, typeDefinitionRegistry)
        for (fieldDefinition in queryDefinition.fieldDefinitions) {
            builder.type(queryDefinition.name) {
                val cypherDirective = fieldDefinition.cypherDirective()
                val type: NodeFacade = typeDefinitionRegistry.getType(fieldDefinition.type.name())
                    .orElseThrow { IllegalStateException("cannot find type " + fieldDefinition.type.name()) }
                    .getNodeType()!!
                val baseDataFetcher = if (cypherDirective != null) {
                    CypherDirectiveHandler(type, true, cypherDirective, fieldDefinition, metaProvider)
                } else {
                    QueryHandler(type, fieldDefinition, metaProvider)
                }
                it.dataFetcher(fieldDefinition.name, baseDataFetcher)
            }
        }

        val queries = augmentations.mapNotNull { it.query }
        queryDefinition = mergeOperations(queryDefinition, typeDefinitionRegistry, queries, builder)

        var mutationDefinition = getOrCreateObjectTypeDefinition("Mutation", schemaDefinition, typeDefinitionRegistry)
        for (fieldDefinition in mutationDefinition.fieldDefinitions) {
            val cypherDirective: Translator.Cypher? = fieldDefinition.cypherDirective()
            if (cypherDirective != null) {
                builder.type(mutationDefinition.name) {
                    val type: NodeFacade = typeDefinitionRegistry.getType(fieldDefinition.type.name())
                        .orElseThrow { IllegalStateException("cannot find type " + fieldDefinition.type.name()) }
                        .getNodeType()!!
                    val baseDataFetcher = CypherDirectiveHandler(type, false, cypherDirective, fieldDefinition, metaProvider)
                    it.dataFetcher(fieldDefinition.name, baseDataFetcher)
                }
            }
        }
        val mutations = augmentations.flatMap { listOf(it.create, it.update, it.delete, it.merge) }
        mutationDefinition = mergeOperations(mutationDefinition, typeDefinitionRegistry, mutations, builder)

        val newSchemaDef = schemaDefinition.transform {
            it.operationTypeDefinition(OperationTypeDefinition("query", TypeName(queryDefinition.name)))
                .operationTypeDefinition(OperationTypeDefinition("mutation", TypeName(mutationDefinition.name))).build()
        }

        typeDefinitionRegistry.remove(schemaDefinition)
        typeDefinitionRegistry.add(newSchemaDef)
        return typeDefinitionRegistry
    }

    /**
     * add the given operations to the corresponding ObjectTypeDefinition
     */
    private fun mergeOperations(
            objectTypeDefinition: ObjectTypeDefinition,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            dataFetcher: List<BaseDataFetcher?>,
            builder: RuntimeWiring.Builder)
            : ObjectTypeDefinition {

        dataFetcher
            .filterNotNull()
            .filter { objectTypeDefinition.fieldDefinitions.none { fd -> it.fieldDefinition.name == fd.name } }
            .distinctBy { it.fieldDefinition.name }
            .let { queries ->
                if (queries.isNotEmpty()) {
                    typeDefinitionRegistry.remove(objectTypeDefinition)
                    typeDefinitionRegistry.add(objectTypeDefinition.transform { qdb ->
                        queries.forEach { df ->
                            qdb.fieldDefinition(df.fieldDefinition)
                            builder.type(objectTypeDefinition.name) { it.dataFetcher(df.fieldDefinition.name, df) }
                        }
                    })
                }
            }
        return objectTypeDefinition
    }

    private fun getOrCreateObjectTypeDefinition(
            name: String,
            schemaDefinition: SchemaDefinition,
            typeDefinitionRegistry: TypeDefinitionRegistry): ObjectTypeDefinition {
        val operationGroup = name.toLowerCase()
        return schemaDefinition.operationTypeDefinitions
            .firstOrNull { it.name == operationGroup }
            ?.let {
                typeDefinitionRegistry
                    .getType(it.typeName, ObjectTypeDefinition::class.java)
                    .orElseThrow { RuntimeException("Could not find type: " + it.typeName) } as ObjectTypeDefinition
            }
                ?: typeDefinitionRegistry.getType(name, ObjectTypeDefinition::class.java)
                    .orElseGet {
                        ObjectTypeDefinition(name).also { typeDefinitionRegistry.add(it) }
                    }
    }

    private fun createRelationshipMutations(
            source: ObjectTypeDefinition,
            objectTypeDefinitions: List<ObjectTypeDefinition>,
            relationTypes: Map<String, ObjectTypeDefinition>?,
            metaProvider: MetaProvider,
            ctx: Translator.Context): List<Augmentation> {

        return source.fieldDefinitions
            .filter { !it.type.inner().isScalar() && it.getDirective(DirectiveConstants.RELATION) != null }
            .mapNotNull { targetField ->
                objectTypeDefinitions.firstOrNull { it.name == targetField.type.inner().name() }
                    ?.let { target ->
                        createRelationshipMutation(ctx, source, target, metaProvider, relationTypes)
                    }
            }
    }
}