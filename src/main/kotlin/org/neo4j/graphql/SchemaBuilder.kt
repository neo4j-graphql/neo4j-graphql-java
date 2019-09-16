package org.neo4j.graphql

import graphql.language.*
import graphql.language.TypeDefinition
import graphql.schema.DataFetcher
import graphql.schema.GraphQLSchema
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

        val builder = RuntimeWiring.newRuntimeWiring()
            .scalar(DynamicProperties.INSTANCE)

        AugmentationProcessor(typeDefinitionRegistry, config, dataFetchingInterceptor, builder).augmentSchema()

        typeDefinitionRegistry
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

        val runtimeWiring = builder.build()

        // todo add new queries, filters, enums etc.
        return SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
    }

    private class AugmentationProcessor(
            val typeDefinitionRegistry: TypeDefinitionRegistry,
            val schemaConfig: SchemaConfig,
            val dataFetchingInterceptor: DataFetchingInterceptor?,
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
            mergeNeo4jEnhancements()

            val interfaceTypeDefinitions = typeDefinitionRegistry.types().values
                .filterIsInstance<InterfaceTypeDefinition>()
                .map { enhanceRelations(it, InterfaceTypeDefinition.CHILD_DEFINITIONS) }
            val objectTypeDefinitions = typeDefinitionRegistry.types().values
                .filterIsInstance<ObjectTypeDefinition>()
                .filter { !it.isNeo4jType() }
                .map { enhanceRelations(it, ObjectTypeDefinition.CHILD_FIELD_DEFINITIONS) }

            wireDefinedOperations(queryDefinition, true)
            wireDefinedOperations(mutationDefinition, false)

            val relationTypes = objectTypeDefinitions
                .filter { it.getDirective(DirectiveConstants.RELATION) != null }
                .map { it.getDirective(DirectiveConstants.RELATION).getArgument(DirectiveConstants.RELATION_NAME).value.toJavaValue().toString() to it }
                .toMap()

            val nodeDefinitions: List<TypeDefinition<*>> = interfaceTypeDefinitions + objectTypeDefinitions
            nodeDefinitions.forEach { createNodeMutation(it.getNodeType()!!) }

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

        private fun mergeNeo4jEnhancements() {
            val directivesSdl = javaClass.getResource("/neo4j.graphql").readText()
            typeDefinitionRegistry.merge(SchemaParser().parse(directivesSdl))
            neo4jTypeDefinitions
                .forEach {
                    val type = typeDefinitionRegistry.getType(it.typeDefinition)
                        .orElseThrow { IllegalStateException("type ${it.typeDefinition} not found") }
                            as ObjectTypeDefinition
                    addInputType(it.inputDefinition, it.inputDefinition, type.fieldDefinitions)
                }
        }

        private fun wireDefinedOperations(queryDefinition: ObjectTypeDefinition, isQuery: Boolean) {
            for (fieldDefinition in queryDefinition.fieldDefinitions) {
                QueryHandler.build(fieldDefinition, isQuery, metaProvider)?.let {
                    addDataFetcher(queryDefinition.name, fieldDefinition.name, it)
                }
            }
        }

        fun addDataFetcher(type: String, name: String, dataFetcher: DataFetcher<Cypher>) {
            val df: DataFetcher<*> = dataFetchingInterceptor?.let {
                DataFetcher { env ->
                    dataFetchingInterceptor.fetchData(env, dataFetcher)
                }
            } ?: dataFetcher
            wiringBuilder.type(type) { runtimeWiring -> runtimeWiring.dataFetcher(name, df) }
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
                addDataFetcher(objectTypeDefinition.name, dataFetcher.fieldDefinition.name, dataFetcher)
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
                        .orElseThrow { RuntimeException("Could not find type: ${it.typeName} in schema") } as ObjectTypeDefinition
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
            if (!schemaConfig.mutation.enabled || schemaConfig.mutation.exclude.contains(source.name)) {
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


        private fun <T : SDLDefinition<*>> enhanceRelations(source: T, childName: String): T {
            var enhanced: T = source
            val fieldDefinitions = enhanced.namedChildren.children[childName]
                    ?: return enhanced
            for ((index, fd) in fieldDefinitions.withIndex()) {
                if (fd !is FieldDefinition
                    || !typeDefinitionRegistry.types().containsKey(fd.type.inner().name())
                    || !fd.isList()) {
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                enhanced = enhanced.withNewChildren(enhanced.namedChildren.transform { b1 ->
                    b1.replaceChild(childName, index, fd.transform { builder ->
                        if (fd.inputValueDefinitions.none { it.name == ProjectionBase.FIRST }) {
                            builder.inputValueDefinition(BaseDataFetcher.input(ProjectionBase.FIRST, TypeName("Int")))
                        }
                        if (fd.inputValueDefinitions.none { it.name == ProjectionBase.OFFSET }) {
                            builder.inputValueDefinition(BaseDataFetcher.input(ProjectionBase.OFFSET, TypeName("Int")))
                        }
                        // TODO implement ordering
//                        if (fd.inputValueDefinitions.none { it.name == ProjectionBase.ORDER_BY }) {
//                            val typeName = fd.type.name()!!
//                            val orderingName = addOrdering(typeName, metaProvider.getNodeType(typeName)!!.fieldDefinitions().filter { it.type.isScalar() })
//                            builder.inputValueDefinition(BaseDataFetcher.input(ProjectionBase.ORDER_BY, TypeName(orderingName)))
//                        }
                    })
                }) as T
            }
            if (source != enhanced) {
                typeDefinitionRegistry.remove(source)
                typeDefinitionRegistry.add(enhanced)
            }
            return enhanced
        }

        private fun createNodeMutation(type: NodeFacade) {
            val typeName = type.name()
            val idField = type.fieldDefinitions().find { it.isID() }
            val relevantFields = type.fieldDefinitions()
                .filter { it.type.isScalar() || it.type.isNeo4jType() }
                .sortedByDescending { it == idField }
            if (relevantFields.isEmpty()) {
                return
            }
            if (schemaConfig.mutation.enabled && !schemaConfig.mutation.exclude.contains(typeName)) {
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
            if (schemaConfig.query.enabled && !schemaConfig.query.exclude.contains(typeName)) {
                addInputType(typeName, relevantFields = relevantFields)
                val filterName = addFilterType(typeName, type.fieldDefinitions())
                val orderingName = addOrdering(typeName, relevantFields)
                queryDefinition = mergeOperation(queryDefinition, QueryHandler.build(type, filterName, orderingName, metaProvider))
            }
        }

        private fun addInputType(typeName: String, inputName: String = "_${typeName}Input", relevantFields: List<FieldDefinition>): String {
            if (typeDefinitionRegistry.getType(inputName).isPresent) {
                return inputName
            }
            val inputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(inputName)
                .inputValueDefinitions(BaseDataFetcher.getInputValueDefinitions(relevantFields) { true })
                .build()
            typeDefinitionRegistry.add(inputType)
            return inputName
        }

        private fun addOrdering(typeName: String, relevantFields: List<FieldDefinition>): String {
            val orderingName = "_${typeName}Ordering"
            if (typeDefinitionRegistry.getType(orderingName).isPresent) {
                return orderingName
            }
            val ordering = EnumTypeDefinition.newEnumTypeDefinition()
                .name(orderingName)
                .enumValueDefinitions(relevantFields.flatMap { fd ->
                    listOf("_asc", "_desc")
                        .map { EnumValueDefinition.newEnumValueDefinition().name(fd.name + it).build() }
                })
                .build()
            typeDefinitionRegistry.add(ordering)
            return orderingName
        }

        private fun addFilterType(name: String?, fieldArgs: List<FieldDefinition>, handled: MutableSet<String> = mutableSetOf()): String {
            val filterName = "_${name}Filter"
            if (typeDefinitionRegistry.getType(filterName).isPresent || handled.contains(filterName)) {
                return filterName
            }
            handled.add(filterName)
            val builder = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(filterName)
            listOf("AND", "OR", "NOT")
                .forEach { builder.inputValueDefinition(BaseDataFetcher.input(it, ListType(NonNullType(TypeName(filterName))))) }
            fieldArgs
                .filter { it.dynamicPrefix(metaProvider) == null } // TODO currently we do not support filtering on dynamic properties
                .forEach { field ->
                    val typeDefinition = typeDefinitionRegistry.getType(field.type).orElse(null)
                    val type = if (field.type.isScalar() || typeDefinition is EnumTypeDefinition || field.isNeo4jType()) {
                        field.type.inner().inputType()
                    } else {
                        val objectName = field.type.name()
                        val subFilterName = addFilterType(objectName,
                                metaProvider.getNodeType(objectName)
                                    ?.fieldDefinitions()
                                        ?: throw IllegalArgumentException("type $objectName not found"),
                                handled)
                        TypeName(subFilterName)
                    }
                    Operators.forType(field.type, typeDefinition)
                        .forEach { op ->
                            val filterType = if (op.list) {
                                ListType(type)
                            } else {
                                type
                            }
                            builder.inputValueDefinition(BaseDataFetcher.input(op.fieldName(field.name), filterType))
                        }
                }
            typeDefinitionRegistry.add(builder.build())
            return filterName
        }
    }
}