package org.neo4j.graphql

import graphql.language.*
import graphql.language.TypeDefinition
import graphql.scalars.`object`.ObjectScalar
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.handler.ProjectionRepository

object SchemaBuilder {
    @JvmStatic
    fun buildSchema(sdl: String, ctx: Translator.Context = Translator.Context()): GraphQLSchema {
        val schemaParser = SchemaParser()
        val baseTypeDefinitionRegistry = schemaParser.parse(sdl)
        val augmentedTypeDefinitionRegistry = augmentSchema(baseTypeDefinitionRegistry, schemaParser, ctx)

        val builder = RuntimeWiring.newRuntimeWiring()
            .scalar(ObjectScalar())
            .type("Query") { it.dataFetcher("hello") { env -> "Hello ${env.getArgument<Any>("what")}!" } }
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
            ctx: Translator.Context): TypeDefinitionRegistry {
        val directivesSdl = javaClass.getResource("/neo4j_directives.graphql").readText()
        typeDefinitionRegistry.merge(schemaParser.parse(directivesSdl))

        val projectionRepository = ProjectionRepository()

        val interfaceTypeDefinitions = typeDefinitionRegistry.types().values.filterIsInstance<InterfaceTypeDefinition>()
        val objectTypeDefinitions = typeDefinitionRegistry.types().values.filterIsInstance<ObjectTypeDefinition>()

        val nodeDefinitions: List<TypeDefinition<*>> = interfaceTypeDefinitions + objectTypeDefinitions

        val nodeMutations = nodeDefinitions.map { createNodeMutation(ctx, it.getNodeType()!!, typeDefinitionRegistry, projectionRepository) }
        val relationTypes = objectTypeDefinitions
            .filter { it.getDirective(DirectiveConstants.RELATION) != null }
            .map { it.getDirective(DirectiveConstants.RELATION).getArgument(DirectiveConstants.RELATION_NAME).value.toJavaValue().toString() to it }
            .toMap()

        val relMutations = objectTypeDefinitions.flatMap { source ->
            createRelationshipMutations(source, objectTypeDefinitions, relationTypes, typeDefinitionRegistry, projectionRepository, ctx)
        } + relationTypes.values.map { createNodeMutation(ctx, it.getNodeType()!!, typeDefinitionRegistry, projectionRepository) }

        val augmentations = nodeMutations + relMutations

        augmentations
            .flatMap { listOf(it.filterType, it.ordering, it.inputType) }
            .filterNotNull()
            .forEach { typeDefinitionRegistry.add(it as SDLDefinition<*>) }

        val schemaDefinition = typeDefinitionRegistry.schemaDefinition().orElseGet { SchemaDefinition.newSchemaDefinition().build() }
        val operations = schemaDefinition.operationTypeDefinitions
            .associate { it.name to typeDefinitionRegistry.getType(it.typeName).orElseThrow { RuntimeException("Could not find type: " + it.typeName) } as ObjectTypeDefinition }
            .toMap()


        val queryDefinition = operations.getOrElse("query") {
            typeDefinitionRegistry.getType("Query", ObjectTypeDefinition::class.java).orElseGet {
                ObjectTypeDefinition("Query").also { typeDefinitionRegistry.add(it) }
            }
        }
        augmentations
            .mapNotNull { it.query }
            .filter { queryDefinition.fieldDefinitions.none { fd -> it.fieldDefinition.name == fd.name } }
            .distinctBy { it.fieldDefinition.name }
            .let { queries ->
                if (queries.isNotEmpty()) {
                    typeDefinitionRegistry.remove(queryDefinition)
                    typeDefinitionRegistry.add(queryDefinition.transform { qdb ->
                        queries.forEach { query ->
                            qdb.fieldDefinition(query.fieldDefinition)
                        }
                    })
                }
            }

        val mutationDefinition = operations.getOrElse("mutation") {
            typeDefinitionRegistry.getType("Mutation", ObjectTypeDefinition::class.java).orElseGet {
                ObjectTypeDefinition("Mutation").also { typeDefinitionRegistry.add(it) }
            }
        }

        augmentations
            .flatMap { listOf(it.create, it.update, it.delete, it.merge) }
            .filterNotNull()
            .filter { mutationDefinition.fieldDefinitions.none { fd -> it.fieldDefinition.name == fd.name } }
            .distinctBy { it.fieldDefinition.name }
            .let { queries ->
                if (queries.isNotEmpty()) {
                    typeDefinitionRegistry.remove(mutationDefinition)
                    typeDefinitionRegistry.add(mutationDefinition.transform { qdb ->
                        queries.forEach { query ->
                            qdb.fieldDefinition(query.fieldDefinition)
                        }
                    })
                }
            }

        val newSchemaDef = schemaDefinition.transform {
            it.operationTypeDefinition(OperationTypeDefinition("query", TypeName(queryDefinition.name)))
                .operationTypeDefinition(OperationTypeDefinition("mutation", TypeName(mutationDefinition.name))).build()
        }

        typeDefinitionRegistry.remove(schemaDefinition)
        typeDefinitionRegistry.add(newSchemaDef)

        return typeDefinitionRegistry
    }

    private fun createRelationshipMutations(
            source: ObjectTypeDefinition,
            objectTypeDefinitions: List<ObjectTypeDefinition>,
            relationTypes: Map<String, ObjectTypeDefinition>?,
            typeDefinitionRegistry: TypeDefinitionRegistry,
            projectionRepository: ProjectionRepository,
            ctx: Translator.Context): List<Augmentation> {

        return source.fieldDefinitions
            .filter { !it.type.inner().isScalar() && it.getDirective(DirectiveConstants.RELATION) != null }
            .mapNotNull { targetField ->
                objectTypeDefinitions.firstOrNull { it.name == targetField.type.inner().name() }
                    ?.let { target ->
                        createRelationshipMutation(ctx, source, target, typeDefinitionRegistry, projectionRepository, relationTypes)
                    }
            }
    }
}