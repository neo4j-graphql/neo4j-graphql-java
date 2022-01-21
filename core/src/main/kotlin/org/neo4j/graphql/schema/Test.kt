package org.neo4j.graphql.schema

import graphql.language.*
import graphql.schema.DataFetcher
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.Constants.FORBIDDEN_RELATIONSHIP_PROPERTY_DIRECTIVES
import org.neo4j.graphql.Constants.RESERVED_INTERFACE_FIELDS
import org.neo4j.graphql.DirectiveConstants.AUTH
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Model
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.directives.ExcludeDirective
import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.merge.TypeDefinitionRegistryMerger

class Test(
    typeDefinitionRegistry: TypeDefinitionRegistry,
    neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
) : AugmentationHandler(SchemaConfig(), typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

    private val relationAugmentationTypesFactory = RelationAugmentationTypesFactory(
        schemaConfig,
        typeDefinitionRegistry,
        neo4jTypeDefinitionRegistry
    )
    private val relationshipFields = mutableMapOf<String, RelationshipProperties?>()
    private val interfaces = mutableMapOf<String, Interface?>()

    fun augment() {
        TypeDefinitionRegistryMerger.mergeExtensions(typeDefinitionRegistry)

        val model = createModel()

        model.nodes.forEach { augmentNode(it) }

        typeDefinitionRegistry.getTypes(InterfaceTypeDefinition::class.java).forEach { interfaceTypeDefinition ->
            typeDefinitionRegistry.replace(interfaceTypeDefinition.transform { builder ->
                builder.addNonLibDirectives(interfaceTypeDefinition)
                builder.definitions(interfaceTypeDefinition.fieldDefinitions.map { field ->
                    field.transform { fieldBuilder -> fieldBuilder.addNonLibDirectives(field) }
                })
            })
        }


        listOf(typeDefinitionRegistry.queryType(), typeDefinitionRegistry.mutationType())
            .filterNotNull()
            .forEach { obj ->
                typeDefinitionRegistry.replace(obj.transform { objBuilder ->
                    objBuilder.addNonLibDirectives(obj)
                    objBuilder.fieldDefinitions(obj.fieldDefinitions.map { field ->
                        field.transform { fieldBuilder -> fieldBuilder.addNonLibDirectives(field) }
                    })
                })
            }


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
                .onEach { addNode(it) }
                .map { it.name }
                .toSet()
        }

        model.nodes
            .flatMap { interfaze ->
                interfaze.interfaces +
                        interfaze.fields.filterIsInstance<RelationField<*>>().mapNotNull { it.interfaze } +
                        interfaze.interfaces.flatMap { it.interfaces }
            }
            .distinctBy { it.name }
            .forEach { addInterface(it) }

    }

    private fun augmentNode(node: Node) {
        typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(node.name)?.let { typeDefinitionRegistry.remove(it) }

        fun addWhere(args: MutableList<InputValueDefinition>) {
            relationAugmentationTypesFactory.addWhereType(node)
                ?.let { args += inputValue(Constants.WHERE, it.asType()) }
        }

        if (node.exclude?.operations?.contains(ExcludeDirective.ExcludeOperation.READ) != true) {
            addQueryField(node.plural.decapitalize(), NonNullType(ListType(node.name.asRequiredType()))) { args ->
                addWhere(args)
                addOptions(node).let { args += inputValue(Constants.OPTIONS, it.asType()) }
            }

            addQueryField(node.plural.decapitalize() + "Count", Constants.Types.Int.makeRequired()) { args ->
                addWhere(args)
            }

            val aggregationSelection = addAggregationSelectionType(node)
            addQueryField(node.plural.decapitalize() + "Aggregate", aggregationSelection.asRequiredType()) { args ->
                addWhere(args)
            }
        }

        if (node.exclude?.operations?.contains(ExcludeDirective.ExcludeOperation.CREATE) != true) {
            relationAugmentationTypesFactory.addCreateInputType(node)?.let { inputType ->
                val responseType = addResponseType("Create", node)
                addMutationField("create" + node.plural, responseType.asRequiredType()) { args ->
                    args += inputValue(Constants.INPUT_FIELD, NonNullType(ListType(inputType.asRequiredType())))
                }
            }
        }

        if (node.exclude?.operations?.contains(ExcludeDirective.ExcludeOperation.DELETE) != true) {
            addMutationField("delete" + node.plural, Constants.Types.DeleteInfo.makeRequired()) { args ->
                addWhere(args)
                relationAugmentationTypesFactory.addRelationDeleteInputField(node.name, node.relationFields)
                    ?.let { args += inputValue(Constants.DELETE_FIELD, it.asType()) }
            }
        }

        if (node.exclude?.operations?.contains(ExcludeDirective.ExcludeOperation.UPDATE) != true) {
            val responseType = addResponseType("Update", node)
            addMutationField("update" + node.plural, responseType.asRequiredType()) { args ->
                addWhere(args)
                relationAugmentationTypesFactory.addUpdateInputType(node)
                    ?.let { args += inputValue(Constants.UPDATE_FIELD, it.asType()) }

                relationAugmentationTypesFactory.addRelationConnectInputField(node.name, node.relationFields)
                    ?.let { args += inputValue(Constants.CONNECT_FIELD, it.asType()) }

                relationAugmentationTypesFactory.addRelationDisconnectInputField(node.name, node.relationFields)
                    ?.let { args += inputValue(Constants.DISCONNECT_FIELD, it.asType()) }

                relationAugmentationTypesFactory.addRelationInputField(node.name, node.relationFields)
                    ?.let { args += inputValue(Constants.CREATE_FIELD, it.asType()) }

                relationAugmentationTypesFactory.addRelationDeleteInputField(node.name, node.relationFields)
                    ?.let { args += inputValue(Constants.DELETE_FIELD, it.asType()) }

                relationAugmentationTypesFactory.addRelationConnectOrCreateInputField(node.name, node.relationFields)
                    ?.let { args += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.asType()) }
            }
        }
    }

    fun addResponseType(operation: String, node: Node) =
        getOrCreateObjectType("${operation}${node.plural}MutationResponse") { args, _ ->
            args += field(Constants.INFO_FIELD, NonNullType(TypeName(operation + "Info")))
            args += field(node.plural.decapitalize(), NonNullType(ListType(node.name.asRequiredType())))
        }
            ?: throw IllegalStateException("Expected at least the info field")


    fun addInterface(interfaze: Interface) {
        typeDefinitionRegistry.replace(InterfaceTypeDefinition.newInterfaceTypeDefinition()
            .apply {
                name(interfaze.name)
                description(interfaze.description)
                comments(interfaze.comments)
                directives(interfaze.otherDirectives)
                implementz(interfaze.interfaces.map { it.name.asType() })
                definitions(interfaze.fields.filterNot { it.writeonly }
                    .map { mapField(interfaze.name, it) })
            }
            .build()
        )
    }

    fun addNode(node: Node) = getOrCreateObjectType(node.name,
        init = {
            description(node.description)
            comments(node.comments)
            directives(node.otherDirectives)
            implementz(node.interfaces.map { it.name.asType() })
        },
        initFields = { fields, _ ->
            node.fields
                .filterNot { it.writeonly }
                .forEach { field ->
                    fields += mapField(node.name, field)
                    (field as? RelationField)?.node?.let { n ->
                        val aggr =
                            addAggregationSelectionType(
                                node.name + n.name + field.fieldName.capitalize(),
                                n,
                                field
                            )
                        fields += field(field.fieldName + "Aggregate", aggr.asType()) {
                            relationAugmentationTypesFactory.addWhereType(node.name, field)?.let {
                                inputValueDefinition(inputValue(Constants.WHERE, it.asType()))
                            }
                        }
                    }
                }
        }
    )

    fun mapField(parentName: String, field: BaseField<*>): FieldDefinition {
        val args = field.arguments.toMutableList()
        val type = when (field) {
            is ConnectionField<*> -> createConnectionField(field).wrapLike(field.typeMeta.type)
            else -> field.typeMeta.type
        }
        if (field is RelationField) {
            if (field.node != null) {
                relationAugmentationTypesFactory.addWhereType(parentName, field)?.let {
                    args += inputValue(Constants.WHERE, it.asType())
                }
            }
            val optionType = when {
                field.isInterface -> Constants.Types.QueryOptions
                field.isUnion -> Constants.Types.QueryOptions
                else -> addOptions(
                    field.node
                        ?: throw IllegalArgumentException("no node on ${field.connectionPrefix}.${field.fieldName}")
                ).asType()
            }
            args += inputValue(Constants.OPTIONS, optionType)
        }
        if (field is ConnectionField && field.relationshipField.node != null) {
            relationAugmentationTypesFactory.addConnectionWhereType(parentName, field)
                ?.let { args += inputValue(Constants.WHERE, it.asType()) }
            field.relationshipField.node?.let {
                args += inputValue(Constants.FIRST, Constants.Types.Int)
                args += inputValue(Constants.AFTER, Constants.Types.String)
            }
            getConnectionSortType(field)
                ?.let { args += inputValue(Constants.SORT, ListType(it.asRequiredType())) }
        }
        return field(field.fieldName, type) {
            description(field.description)
            comments(field.comments)
            directives(field.otherDirectives)
            inputValueDefinitions(args)
        }
    }

    // TODO field-aggregation-composer.ts
    fun addAggregationSelectionType(baseTypeName: String, refNode: Node, rel: RelationField<*>) =
        getOrCreateObjectType("${baseTypeName}AggregationSelection") { fields, _ ->
            fields += field(Constants.COUNT, Constants.Types.Int.makeRequired())
            createAggregationField("${baseTypeName}NodeAggregateSelection", refNode.fields)
                ?.let { fields += field(Constants.NODE_FIELD, it.asType()) }
            createAggregationField("${baseTypeName}EdgeAggregateSelection", rel.properties?.fields ?: emptyList())
                ?.let { fields += field(Constants.EDGE_FIELD, it.asType()) }
        } ?: throw IllegalStateException("Expected at least the count field")

    fun createAggregationField(name: String, relFields: List<BaseField<*>>) = getOrCreateObjectType(name) { fields, _ ->
        relFields
            .filterIsInstance<PrimitiveField<*>>()
            .filterNot { it.typeMeta.type.isList() }
            .forEach { field ->
                getOrCreateAggregationType(field.typeMeta.type)
                    ?.let { fields += field(field.fieldName, NonNullType(it)) }
            }
    }

    private fun createConnectionField(field: ConnectionField<*>): String {
        val initFields: (fields: MutableList<FieldDefinition>, name: String) -> Unit = { fields, _ ->
            createRelationshipField(field).let {
                fields += field(Constants.EDGES_FIELD, NonNullType(ListType(it.asRequiredType())))
            }
            fields += field(Constants.TOTAL_COUNT, NonNullType(Constants.Types.Int))
            fields += field(Constants.PAGE_INFO, NonNullType(Constants.Types.PageInfo))
        }
        if (field.relationshipField.node != null) {
            // If the field is a concrete node
            val interfaceDefiningField = field.interfaceDefinition as? ConnectionField<*>
            if (interfaceDefiningField != null){
                return getOrCreateObjectType(
                    interfaceDefiningField.typeMeta.type.name(),
                    initFields = initFields,
                )
                    ?: throw IllegalStateException("Expected ${field.typeMeta.type.name()} to have fields")
            }
        }
        return if (field.owner is Interface) {
            getOrCreateInterfaceType(field.typeMeta.type.name(), initFields = initFields)
        } else {
            getOrCreateObjectType(
                field.typeMeta.type.name(),
                init = {
                    (field.owner as? Node)?.interfaces?.forEach { interfaze ->
                        interfaze.fields.filterIsInstance<ConnectionField<*>>()
                            .find { it.fieldName == field.fieldName }
                            ?.let { createConnectionField(it) }
                            ?.let { implementz(it.asType()) }
                    }
                },
                initFields = initFields,
            )
        }
            ?: throw IllegalStateException("Expected ${field.typeMeta.type.name()} to have fields")
    }

    private fun createRelationshipField(field: ConnectionField<*>): String {
        val initFields: (fields: MutableList<FieldDefinition>, name: String) -> Unit = { fields, _ ->
            fields += field(Constants.CURSOR_FIELD, Constants.Types.String.makeRequired())
            fields += field(Constants.NODE_FIELD, NonNullType(field.relationshipField.typeMeta.type.inner()))
            field.relationship.properties
                ?.let { typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it.interfaceName) }
                ?.let { definition ->
                    fields += definition.fieldDefinitions.map { fieldDefinition ->
                        fieldDefinition.transform { it.addNonLibDirectives(fieldDefinition) }
                    }
                }
        }
        return if (field.owner is Interface) {
            getOrCreateInterfaceType(field.relationshipTypeName, init = {
                field.relationship.properties?.let { implementz(it.interfaceName.asType()) }
            }, initFields)
        } else {
            getOrCreateObjectType(field.relationshipTypeName, init = {
                field.relationship.properties?.let { implementz(it.interfaceName.asType()) }
                (field.owner as? Node)?.interfaces?.forEach { interfaze ->
                    interfaze.fields.filterIsInstance<ConnectionField<*>>()
                        .find { it.fieldName == field.fieldName }
                        ?.let { createRelationshipField(it) }
                        ?.let { implementz(it.asType()) }
                }
            }, initFields)
        } ?: throw IllegalStateException("Expected ${field.relationshipTypeName} to have fields")

    }


    // TODO move into org.neo4j.graphql.schema.RelationAugmentationTypesFactory
    private fun getConnectionSortType(field: ConnectionField<*>) =
        getOrCreateInputObjectType(field.typeMeta.type.name() + "Sort") { fields, _ ->
            field.relationshipField.properties
                ?.let { getPropertySortType(it) }
                ?.let { fields += inputValue(Constants.EDGE_FIELD, it.asType()) }
            field.relationshipField.node
                ?.let { getSortType(it) }
                ?.let { fields += inputValue(Constants.NODE_FIELD, it.asType()) }
        }

    private fun getPropertySortType(properties: RelationshipProperties) =
        getOrCreateInputObjectType(properties.interfaceName + "Sort") { fields, _ ->
            properties.fields.forEach {
                fields += inputValue(it.fieldName, Constants.Types.SortDirection)
            }
        }

    private fun getSortType(node: Node) =
        getOrCreateInputObjectType(
            node.name + "Sort",
            init = { description("Fields to sort ${node.plural} by. The order in which sorts are applied is not guaranteed when specifying many fields in one ${node.name}Sort object.".asDescription()) },
            initFields = { fields, _ ->
                node.sortableFields.forEach {
                    fields += inputValue(it.fieldName, Constants.Types.SortDirection)
                }
            }
        )


    override fun createDataFetcher(
        operationType: OperationType,
        fieldDefinition: FieldDefinition
    ): DataFetcher<Cypher>? {
        TODO("Not yet implemented")
    }

    fun createModel(): Model {
        val queryTypeName = typeDefinitionRegistry.queryTypeName()
        val mutationTypeName = typeDefinitionRegistry.mutationTypeName()
        val subscriptionTypeName = typeDefinitionRegistry.subscriptionTypeName()
        val reservedTypes = setOf(queryTypeName, mutationTypeName, subscriptionTypeName)

        val objectNodes = typeDefinitionRegistry.getTypes(ObjectTypeDefinition::class.java)
            .filterNot { reservedTypes.contains(it.name) }


        val nodes = objectNodes.map { node ->
            NodeFactory.createNode(
                node,
                typeDefinitionRegistry,
                this::getOrCreateRelationshipProperties,
                this::getOrCreateInterface,
            )
        }
        val nodesByName = nodes.associateBy { it.name }
        val implementations = mutableMapOf<Interface, MutableList<Node>>()
        nodes.forEach { node ->
            node.relationFields.forEach {
                it.unionNodes = it.union.mapNotNull { nodesByName[it] }
                it.node = nodesByName[it.typeMeta.type.name()]
            }
            node.interfaces.forEach {
                implementations.computeIfAbsent(it) { mutableListOf() }.add(node)
            }
        }
        implementations.forEach { (interfaze, impls) ->
            interfaze.implementations = impls
            interfaze.relationFields.forEach {
                it.unionNodes = it.union.mapNotNull { nodesByName[it] }
                it.node = nodesByName[it.typeMeta.type.name()]
            }
        }
        val relationships = nodes.flatMap { it.relationFields }.map { it.relationship }

        return Model(nodes, relationships)
    }

    fun getOrCreateInterface(name: String): Interface? {
        return interfaces.computeIfAbsent(name) {
            val interfaze =
                typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it) ?: return@computeIfAbsent null
            return@computeIfAbsent InterfaceFactory.createInterface(
                interfaze,
                typeDefinitionRegistry,
                ::getOrCreateRelationshipProperties,
                ::getOrCreateInterface,
            )
        }
    }

    fun getOrCreateRelationshipProperties(name: String): RelationshipProperties? {
        return relationshipFields.computeIfAbsent(name) {
            val relationship =
                typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it) ?: return@computeIfAbsent null

            if (relationship.directives.find { it.name == AUTH } != null) {
                throw IllegalArgumentException("Cannot have @auth directive on relationship properties interface")
            }

            relationship.fieldDefinitions?.forEach { field ->
                RESERVED_INTERFACE_FIELDS[field.name]?.let { message -> throw IllegalArgumentException(message) }
                field.directives.forEach {
                    if (FORBIDDEN_RELATIONSHIP_PROPERTY_DIRECTIVES.contains(it.name)) {
                        throw IllegalArgumentException("Cannot have @${it.name} directive on relationship property");
                    }
                }
            }

            val fields = FieldFactory.creteFields<RelationshipProperties>(
                relationship,
                typeDefinitionRegistry,
                this::getOrCreateRelationshipProperties,
                this::getOrCreateInterface,
            ).onEach { field ->
                if (field !is ScalarField) {
                    throw IllegalStateException("Field $name.${field.fieldName} is expected to be of scalar type")
                }
            }
                .filterIsInstance<ScalarField<RelationshipProperties>>()
            RelationshipProperties(name, fields)
        }
    }

}
