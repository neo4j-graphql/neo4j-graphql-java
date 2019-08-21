package org.neo4j.graphql

import graphql.language.ObjectTypeDefinition
import graphql.language.OperationTypeDefinition
import graphql.language.SchemaDefinition
import graphql.language.TypeName
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry

object SchemaBuilder {

    @JvmStatic
    fun buildSchema(sdl: String, ctx: SchemaConfig = SchemaConfig()): GraphQLSchema {
        val schemaParser = SchemaParser()
        val baseTypeDefinitionRegistry = schemaParser.parse(sdl)
        val augmentedTypeDefinitionRegistry = augmentSchema(baseTypeDefinitionRegistry, schemaParser, ctx)

        val runtimeWiring = RuntimeWiring.newRuntimeWiring()
            .type("Query") { it.dataFetcher("hello") { env -> "Hello ${env.getArgument<Any>("what")}!" } }
            .build()

        val schemaGenerator = SchemaGenerator()
        return schemaGenerator.makeExecutableSchema(augmentedTypeDefinitionRegistry, runtimeWiring)
            .transform { sc -> sc.build() } // todo add new queries, filters, enums etc.
    }

    private fun augmentSchema(typeDefinitionRegistry: TypeDefinitionRegistry, schemaParser: SchemaParser, ctx: SchemaConfig): TypeDefinitionRegistry {
        val directivesSdl = """
            enum RelationDirection {
               IN
               OUT
               BOTH
            }
            directive @relation(name:String, direction: RelationDirection = OUT, from: String = "from", to: String = "to") on FIELD_DEFINITION | OBJECT
            directive @cypher(statement:String) on FIELD_DEFINITION
            directive @property(name:String) on FIELD_DEFINITION
        """
        typeDefinitionRegistry.merge(schemaParser.parse(directivesSdl))
        typeDefinitionRegistry.merge(schemaParser.parse(neo4jTypesSdl()))


        val objectTypeDefinitions = typeDefinitionRegistry.types().values
            .filterIsInstance<ObjectTypeDefinition>()
            .filter { !it.isNeo4jType() }
        val nodeMutations = objectTypeDefinitions.map { createNodeMutation(ctx, it) }
        val relMutations = objectTypeDefinitions.flatMap { source ->
            createRelationshipMutations(source, objectTypeDefinitions, ctx)
        }
        val augmentations = nodeMutations + relMutations

        val augmentedTypesSdl = augmentations
            .flatMap { it -> listOf(it.filterType, it.ordering, it.inputType).filter { it.isNotBlank() } }
            .joinToString("\n")
        if (augmentedTypesSdl.isNotBlank()) {
            typeDefinitionRegistry.merge(schemaParser.parse(augmentedTypesSdl))
        }

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
            .filter { it.query.isNotBlank() && queryDefinition.fieldDefinitions.none { fd -> it.query.startsWith(fd.name + "(") } }
            .map { it.query }.let { it ->
                if (it.isNotEmpty()) {
                    val newQueries = schemaParser
                        .parse("type AugmentedQuery { ${it.joinToString("\n")} }")
                        .getType("AugmentedQuery").get() as ObjectTypeDefinition
                    typeDefinitionRegistry.remove(queryDefinition)
                    typeDefinitionRegistry.add(queryDefinition.transform { qdb -> newQueries.fieldDefinitions.forEach { qdb.fieldDefinition(it) } })
                }
            }

        val mutationDefinition = operations.getOrElse("mutation") {
            typeDefinitionRegistry.getType("Mutation", ObjectTypeDefinition::class.java).orElseGet {
                ObjectTypeDefinition("Mutation").also { typeDefinitionRegistry.add(it) }
            }
        }
        augmentations.flatMap { it ->
            listOf(it.create, it.update, it.delete, it.merge)
                .filter { it.isNotBlank() && mutationDefinition.fieldDefinitions.none { fd -> it.startsWith(fd.name + "(") } }
        }
            .let { it ->
                if (it.isNotEmpty()) {
                    val newQueries = schemaParser
                        .parse("type AugmentedMutation { ${it.joinToString("\n")} }")
                        .getType("AugmentedMutation").get() as ObjectTypeDefinition
                    typeDefinitionRegistry.remove(mutationDefinition)
                    typeDefinitionRegistry.add(mutationDefinition.transform { mdb -> newQueries.fieldDefinitions.forEach { mdb.fieldDefinition(it) } })
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

    private fun createRelationshipMutations(source: ObjectTypeDefinition,
            objectTypeDefinitions: List<ObjectTypeDefinition>,
            ctx: SchemaConfig): List<Augmentation> = source.fieldDefinitions
        .filter { !it.type.inner().isScalar() && it.getDirective("relation") != null }
        .mapNotNull { targetField ->
            objectTypeDefinitions.firstOrNull { it.name == targetField.type.inner().name() }
                ?.let { target ->
                    createRelationshipMutation(ctx, source, target)
                }
        }
}