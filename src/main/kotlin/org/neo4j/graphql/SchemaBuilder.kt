package org.neo4j.graphql

import graphql.language.*
import graphql.language.TypeDefinition
import graphql.scalars.`object`.ObjectScalar
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.handler.*
import org.neo4j.graphql.handler.relation.CreateRelationHandler
import org.neo4j.graphql.handler.relation.CreateRelationTypeHandler
import org.neo4j.graphql.handler.relation.DeleteRelationHandler

object SchemaBuilder {
    @JvmStatic
    fun buildSchema(sdl: String, ctx: Translator.Context = Translator.Context()): GraphQLSchema {
        val typeDefinitionRegistry = SchemaParser().parse(sdl)
        val builder = RuntimeWiring.newRuntimeWiring()
            .scalar(ObjectScalar())

        AugmentationProcessor(typeDefinitionRegistry, ctx, builder).augmentSchema()

        typeDefinitionRegistry
            .getTypes(InterfaceTypeDefinition::class.java)
            .forEach { typeDefinition -> builder.type(typeDefinition.name) { it.typeResolver { null } } }

        val runtimeWiring = builder.build()

        // todo add new queries, filters, enums etc.
        return SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
    }

    private class AugmentationProcessor(
            val typeDefinitionRegistry: TypeDefinitionRegistry,
            val ctx: Translator.Context,
            val wiringBuilder: RuntimeWiring.Builder
    ) {
        private val metaProvider = TypeRegistryMetaProvider(typeDefinitionRegistry)
        private var schemaDefinition: SchemaDefinition
        private var queryDefinition: ObjectTypeDefinition
        private var mutationDefinition: ObjectTypeDefinition

        init {
            this.schemaDefinition = typeDefinitionRegistry
                .schemaDefinition()
                .orElseGet { SchemaDefinition.newSchemaDefinition().build() }
            this.queryDefinition = getOrCreateObjectTypeDefinition("Query")
            this.mutationDefinition = getOrCreateObjectTypeDefinition("Mutation")
        }

        fun augmentSchema() {
            mergeDirectives()
            wireDefinedOperations(queryDefinition, true)
            wireDefinedOperations(mutationDefinition, false)

            val interfaceTypeDefinitions = typeDefinitionRegistry.types().values.filterIsInstance<InterfaceTypeDefinition>()
            val objectTypeDefinitions = typeDefinitionRegistry.types().values.filterIsInstance<ObjectTypeDefinition>()

            val nodeDefinitions: List<TypeDefinition<*>> = interfaceTypeDefinitions + objectTypeDefinitions
            nodeDefinitions.forEach { createNodeMutation(it.getNodeType()!!) }

            val relationTypes = objectTypeDefinitions
                .filter { it.getDirective(DirectiveConstants.RELATION) != null }
                .map { it.getDirective(DirectiveConstants.RELATION).getArgument(DirectiveConstants.RELATION_NAME).value.toJavaValue().toString() to it }
                .toMap()

            objectTypeDefinitions.forEach { createRelationshipMutations(it, objectTypeDefinitions, relationTypes) }
            relationTypes.values.forEach { createNodeMutation(it.getNodeType()!!) }

            recreateSchema()
        }

        private fun recreateSchema() {
            val newSchemaDef = schemaDefinition.transform {
                it.operationTypeDefinition(OperationTypeDefinition("query", TypeName(queryDefinition.name)))
                    .operationTypeDefinition(OperationTypeDefinition("mutation", TypeName(mutationDefinition.name))).build()
            }

            typeDefinitionRegistry.remove(schemaDefinition)
            typeDefinitionRegistry.add(newSchemaDef)
            schemaDefinition = newSchemaDef
        }

        private fun mergeDirectives() {
            val directivesSdl = javaClass.getResource("/neo4j_directives.graphql").readText()
            typeDefinitionRegistry.merge(SchemaParser().parse(directivesSdl))
        }

        private fun wireDefinedOperations(queryDefinition: ObjectTypeDefinition, isQuery: Boolean) {
            for (fieldDefinition in queryDefinition.fieldDefinitions) {
                QueryHandler.build(fieldDefinition, isQuery, metaProvider)?.let {
                    wiringBuilder.type(queryDefinition.name) { runtimeWiring -> runtimeWiring.dataFetcher(fieldDefinition.name, it) }
                }
            }
        }

        /**
         * add the given operation to the corresponding ObjectTypeDefinition
         */
        private fun mergeOperation(objectTypeDefinition: ObjectTypeDefinition, dataFetcher: BaseDataFetcher?)
                : ObjectTypeDefinition {
            if (dataFetcher == null) {
                return objectTypeDefinition
            }
            if (objectTypeDefinition.fieldDefinitions.any { fd -> dataFetcher.fieldDefinition.name == fd.name }) {
                return objectTypeDefinition // definition already exists
            }
            typeDefinitionRegistry.remove(objectTypeDefinition)
            val transformedTypeDefinition = objectTypeDefinition.transform { qdb ->
                qdb.fieldDefinition(dataFetcher.fieldDefinition)
                wiringBuilder.type(objectTypeDefinition.name) { it.dataFetcher(dataFetcher.fieldDefinition.name, dataFetcher) }
            }
            typeDefinitionRegistry.add(transformedTypeDefinition)
            return transformedTypeDefinition
        }

        private fun getOrCreateObjectTypeDefinition(name: String): ObjectTypeDefinition {
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
                relationTypes: Map<String, ObjectTypeDefinition>?) {
            if (!ctx.mutation.enabled || ctx.mutation.exclude.contains(source.name)) {
                return
            }
            source.fieldDefinitions
                .filter { !it.type.inner().isScalar() && it.getDirective(DirectiveConstants.RELATION) != null }
                .mapNotNull { targetField ->
                    objectTypeDefinitions.firstOrNull { it.name == targetField.type.inner().name() }
                        ?.let { target ->
                            mutationDefinition = mergeOperation(mutationDefinition, DeleteRelationHandler.build(source, target, metaProvider))
                            mutationDefinition = mergeOperation(mutationDefinition, CreateRelationHandler.build(source, target, relationTypes, metaProvider))
                        }
                }
        }

        private fun createNodeMutation(type: NodeFacade) {
            val typeName = type.name()
            val idField = type.fieldDefinitions().find { it.isID() }
            val scalarFields = type.fieldDefinitions().filter { it.type.isScalar() }.sortedByDescending { it == idField }
            if (scalarFields.isEmpty()) {
                return
            }
            if (ctx.mutation.enabled && !ctx.mutation.exclude.contains(typeName)) {
                if (type is ObjectDefinitionNodeFacade) {
                    mutationDefinition = if (type.isRelationType()) {
                        mergeOperation(mutationDefinition, CreateRelationTypeHandler.build(type, metaProvider))
                    } else {
                        mergeOperation(mutationDefinition, CreateTypeHandler.build(type, metaProvider))
                    }
                }
                mutationDefinition = mergeOperation(mutationDefinition, DeleteHandler.build(type, metaProvider))
                mutationDefinition = mergeOperation(mutationDefinition, MergeOrUpdateHandler.build(type, true, metaProvider))
                mutationDefinition = mergeOperation(mutationDefinition, MergeOrUpdateHandler.build(type, false, metaProvider))
            }
            if (ctx.query.enabled && !ctx.query.exclude.contains(typeName)) {
                val inputType = InputObjectTypeDefinition.newInputObjectDefinition()
                    .name("_${typeName}Input")
                    .inputValueDefinitions(BaseDataFetcher.getInputValueDefinitions(scalarFields) { true })
                    .build()

                val filterType = filterType(typeName, scalarFields)

                val ordering = EnumTypeDefinition.newEnumTypeDefinition()
                    .name("_${typeName}Ordering")
                    .enumValueDefinitions(scalarFields.flatMap { fd ->
                        listOf("_asc", "_desc")
                            .map { EnumValueDefinition.newEnumValueDefinition().name(fd.name + it).build() }
                    })
                    .build()

                typeDefinitionRegistry.add(inputType)
                typeDefinitionRegistry.add(filterType)
                typeDefinitionRegistry.add(ordering)
                queryDefinition = mergeOperation(queryDefinition, QueryHandler.build(type, filterType.name, ordering.name, metaProvider))
            }
        }

        private fun filterType(name: String?, fieldArgs: List<FieldDefinition>): InputObjectTypeDefinition {

            val fName = "_${name}Filter"
            val builder = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(fName)
            listOf("AND", "OR", "NOT")
                .forEach { builder.inputValueDefinition(BaseDataFetcher.input(it, ListType(NonNullType(TypeName(fName))))) }
            // TODO allow also deep filter of relations
            fieldArgs.forEach { field ->
                Operators.forType(field.type)
                    .forEach { op -> builder.inputValueDefinition(BaseDataFetcher.input(op.fieldName(field.name), field.type.inner())) }
            }
            return builder.build()
        }
    }
}