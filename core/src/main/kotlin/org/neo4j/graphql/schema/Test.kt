package org.neo4j.graphql.schema

import graphql.language.*
import org.neo4j.graphql.*
import org.neo4j.graphql.AugmentationBase.Companion.addNonLibDirectives
import org.neo4j.graphql.AugmentationBase.Companion.field
import org.neo4j.graphql.AugmentationBase.Companion.inputValue
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
import java.util.concurrent.ConcurrentHashMap

class Test(ctx: AugmentationContext) : BaseAugmentationV2(ctx) {

    private val relationshipFields = mutableMapOf<String, RelationshipProperties?>()
    private val interfaces = ConcurrentHashMap<String, Interface?>()

    val typeDefinitionRegistry get() = ctx.typeDefinitionRegistry

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
                        interfaze.fields.filterIsInstance<RelationField>().mapNotNull { it.interfaze } +
                        interfaze.interfaces.flatMap { it.interfaces }
            }
            .distinctBy { it.name }
            .forEach { addInterface(it) }

    }

    private fun augmentNode(node: Node) {
        typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(node.name)?.let { typeDefinitionRegistry.remove(it) }

        fun addWhere(args: MutableList<InputValueDefinition>) {
            generateWhereIT(node)
                ?.let { args += inputValue(Constants.WHERE, it.asType()) }
        }

        if (node.exclude?.operations?.contains(ExcludeDirective.ExcludeOperation.READ) != true) {
            addQueryField(node.plural.decapitalize(), NonNullType(ListType(node.name.asRequiredType()))) { args ->
                addWhere(args)
                addOptions(node).let { args += inputValue(Constants.OPTIONS, it.asType()) }
                addFulltextQueryType(node)?.let { args += inputValue(Constants.FULLTEXT, it.asType()) }
            }

            addQueryField(node.plural.decapitalize() + "Count", Constants.Types.Int.makeRequired()) { args ->
                addWhere(args)
                addFulltextQueryType(node)?.let { args += inputValue(Constants.FULLTEXT, it.asType()) }
            }

            val aggregationSelection = addAggregationSelectionType(node)
            addQueryField(node.plural.decapitalize() + "Aggregate", aggregationSelection.asRequiredType()) { args ->
                addWhere(args)
                addFulltextQueryType(node)?.let { args += inputValue(Constants.FULLTEXT, it.asType()) }
            }
        }

        if (node.exclude?.operations?.contains(ExcludeDirective.ExcludeOperation.CREATE) != true) {
            generateContainerCreateInputIT(node)?.let { inputType ->
                val responseType = addResponseType("Create", node)
                addMutationField("create" + node.plural, responseType.asRequiredType()) { args ->
                    args += inputValue(Constants.INPUT_FIELD, NonNullType(ListType(inputType.asRequiredType())))
                }
            }
        }

        if (node.exclude?.operations?.contains(ExcludeDirective.ExcludeOperation.DELETE) != true) {
            addMutationField("delete" + node.plural, Constants.Types.DeleteInfo.makeRequired()) { args ->
                addWhere(args)
                generateContainerDeleteInputIT(node)
                    ?.let { args += inputValue(Constants.DELETE_FIELD, it.asType()) }
            }
        }

        if (node.exclude?.operations?.contains(ExcludeDirective.ExcludeOperation.UPDATE) != true) {
            val responseType = addResponseType("Update", node)
            addMutationField("update" + node.plural, responseType.asRequiredType()) { args ->
                addWhere(args)
                generateContainerUpdateIT(node)
                    ?.let { args += inputValue(Constants.UPDATE_FIELD, it.asType()) }

                generateContainerConnectInputIT(node)
                    ?.let { args += inputValue(Constants.CONNECT_FIELD, it.asType()) }

                generateContainerDisconnectInputIT(node)
                    ?.let { args += inputValue(Constants.DISCONNECT_FIELD, it.asType()) }

                generateContainerRelationCreateInputIT(node)
                    ?.let { args += inputValue(Constants.CREATE_FIELD, it.asType()) }

                generateContainerDeleteInputIT(node)
                    ?.let { args += inputValue(Constants.DELETE_FIELD, it.asType()) }

                generateContainerConnectOrCreateInputIT(node)
                    ?.let { args += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.asType()) }
            }
        }
    }

    private fun addResponseType(operation: String, node: Node) =
        getOrCreateObjectType("${operation}${node.plural}MutationResponse") { args, _ ->
            args += field(Constants.INFO_FIELD, NonNullType(TypeName(operation + "Info")))
            args += field(node.plural.decapitalize(), NonNullType(ListType(node.name.asRequiredType())))
        }
            ?: throw IllegalStateException("Expected at least the info field")


    private fun addInterface(interfaze: Interface) {
        typeDefinitionRegistry.replace(InterfaceTypeDefinition.newInterfaceTypeDefinition()
            .apply {
                name(interfaze.name)
                description(interfaze.description)
                comments(interfaze.comments)
                directives(interfaze.otherDirectives)
                implementz(interfaze.interfaces.map { it.name.asType() })
                definitions(interfaze.fields.filterNot { it.writeonly }
                    .map { mapField(it) })
            }
            .build()
        )
    }

    private fun addNode(node: Node) = getOrCreateObjectType(node.name,
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
                    fields += mapField(field)
                    (field as? RelationField)?.node?.let { n ->
                        val aggr =
                            addAggregationSelectionType(
                                node.name + n.name + field.fieldName.capitalize(),
                                n,
                                field
                            )
                        fields += field(field.fieldName + "Aggregate", aggr.asType()) {
                            generateWhereIT(field)?.let {
                                inputValueDefinition(inputValue(Constants.WHERE, it.asType()))
                            }
                        }
                    }
                }
        }
    )

    private fun mapField(field: BaseField): FieldDefinition {
        val args = field.arguments.toMutableList()
        val type = when (field) {
            is ConnectionField -> createConnectionField(field).wrapLike(field.typeMeta.type)
            else -> field.typeMeta.type
        }
        if (field is RelationField) {
            generateWhereIT(field)?.let {
                args += inputValue(Constants.WHERE, it.asType())
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
        if (field is ConnectionField) {
            generateConnectionWhereIT(field)
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
    private fun addAggregationSelectionType(baseTypeName: String, refNode: Node, rel: RelationField) =
        getOrCreateObjectType("${baseTypeName}AggregationSelection") { fields, _ ->
            fields += field(Constants.COUNT, Constants.Types.Int.makeRequired())
            createAggregationField("${baseTypeName}NodeAggregateSelection", refNode.fields)
                ?.let { fields += field(Constants.NODE_FIELD, it.asType()) }
            createAggregationField("${baseTypeName}EdgeAggregateSelection", rel.properties?.fields ?: emptyList())
                ?.let { fields += field(Constants.EDGE_FIELD, it.asType()) }
        } ?: throw IllegalStateException("Expected at least the count field")

    private fun createAggregationField(name: String, relFields: List<BaseField>) =
        getOrCreateObjectType(name) { fields, _ ->
            relFields
                .filterIsInstance<PrimitiveField>()
                .filterNot { it.typeMeta.type.isList() }
                .forEach { field ->
                    getOrCreateAggregationType(field.typeMeta.type)
                        ?.let { fields += field(field.fieldName, NonNullType(it)) }
                }
        }

    private fun createConnectionField(field: ConnectionField): String {
        return getOrCreateObjectType(
            field.typeMeta.type.name(),
        ) { fields, _ ->
            createRelationshipField(field).let {
                fields += field(Constants.EDGES_FIELD, NonNullType(ListType(it.asRequiredType())))
            }
            fields += field(Constants.TOTAL_COUNT, NonNullType(Constants.Types.Int))
            fields += field(Constants.PAGE_INFO, NonNullType(Constants.Types.PageInfo))
        }
            ?: throw IllegalStateException("Expected ${field.typeMeta.type.name()} to have fields")
    }

    private fun createRelationshipField(field: ConnectionField): String {
        return getOrCreateObjectType(field.relationshipTypeName,
            init = {
                field.properties?.let { implementz(it.interfaceName.asType()) }
                (field.owner as? Node)?.interfaces?.forEach { interfaze ->
                    interfaze.fields.filterIsInstance<ConnectionField>()
                        .find { it.fieldName == field.fieldName }
                        ?.let { createRelationshipField(it) }
                        ?.let { implementz(it.asType()) }
                }
            },
            initFields = { fields, _ ->
                fields += field(Constants.CURSOR_FIELD, Constants.Types.String.makeRequired())
                fields += field(Constants.NODE_FIELD, NonNullType(field.relationshipField.typeMeta.type.inner()))
                field.properties
                    ?.let { typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it.interfaceName) }
                    ?.let { definition ->
                        fields += definition.fieldDefinitions.map { fieldDefinition ->
                            fieldDefinition.transform { it.addNonLibDirectives(fieldDefinition) }
                        }
                    }
            })
            ?: throw IllegalStateException("Expected ${field.relationshipTypeName} to have fields")

    }


    // TODO move into org.neo4j.graphql.schema.relations.RelationAugmentationTypesFactory
    private fun getConnectionSortType(field: ConnectionField) =
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


    private fun createModel(): Model {
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
            node.relationFields.forEach { field ->
                field.unionNodes = field.union.mapNotNull { nodesByName[it] }
                field.node = nodesByName[field.typeMeta.type.name()]
            }
            node.interfaces.forEach {
                implementations.computeIfAbsent(it) { mutableListOf() }.add(node)
            }
        }
        implementations.forEach { (interfaze, impls) ->
            interfaze.implementations = impls
            interfaze.relationFields.forEach { field ->
                field.unionNodes = field.union.mapNotNull { nodesByName[it] }
                field.node = nodesByName[field.typeMeta.type.name()]
            }
        }
        val relationships = nodes.flatMap { it.relationFields }

        return Model(nodes, relationships)
    }

    private fun getOrCreateInterface(name: String): Interface? {
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

    private fun getOrCreateRelationshipProperties(name: String): RelationshipProperties? {
        return relationshipFields.computeIfAbsent(name) {
            val relationship =
                typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(it) ?: return@computeIfAbsent null

            if (relationship.directives.find { directive -> directive.name == AUTH } != null) {
                throw IllegalArgumentException("Cannot have @auth directive on relationship properties interface")
            }

            relationship.fieldDefinitions?.forEach { field ->
                RESERVED_INTERFACE_FIELDS[field.name]?.let { message -> throw IllegalArgumentException(message) }
                field.directives.forEach { directive ->
                    if (FORBIDDEN_RELATIONSHIP_PROPERTY_DIRECTIVES.contains(directive.name)) {
                        throw IllegalArgumentException("Cannot have @${directive.name} directive on relationship property")
                    }
                }
            }

            val fields = FieldFactory.createFields(
                relationship,
                typeDefinitionRegistry,
                this::getOrCreateRelationshipProperties,
                this::getOrCreateInterface,
            ).onEach { field ->
                if (field !is ScalarField) {
                    throw IllegalStateException("Field $name.${field.fieldName} is expected to be of scalar type")
                }
            }
                .filterIsInstance<ScalarField>()
            RelationshipProperties(name, fields)
        }
    }

    private fun addFulltextQueryType(node: Node) = node.fulltextDirective?.indexes?.let { indexes ->
        addInputObjectType("${node.name}Fulltext",
            indexes.map { index ->
                val indexType = addInputObjectType(
                    "${node.name}${index.name.capitalize()}Fulltext",
                    inputValue("phrase", NonNullType(Constants.Types.String)),
                    // TODO normalize operation?
                    inputValue("score_EQUAL", Constants.Types.Int),
                )
                inputValue(index.name, TypeName(indexType))
            }
        )
    }

    private fun addAggregationSelectionType(node: Node): String {
        return getOrCreateObjectType("${node.name}AggregateSelection") { fields, _ ->
            fields += field(Constants.COUNT, NonNullType(Constants.Types.Int))
            fields += node.fields
                .filterIsInstance<PrimitiveField>()
                .filterNot { it.typeMeta.type.isList() }
                .mapNotNull { field ->
                    getOrCreateAggregationType(field.typeMeta.type)?.let { field(field.fieldName, NonNullType(it)) }
                }
        } ?: throw IllegalStateException("Expected at least the count field")
    }

    private fun getOrCreateAggregationType(type: Type<*>): Type<*>? {
        val name = "${type.name()}AggregateSelection"
        ctx.neo4jTypeDefinitionRegistry.getUnwrappedType(name) ?: return null
        return TypeName(name)
    }

    private fun addOptions(node: Node) = getOrCreateInputObjectType("${node.name}Options") { fields, _ ->
        fields += inputValue(Constants.LIMIT, Constants.Types.Int)
        fields += inputValue(Constants.OFFSET, Constants.Types.Int)
        addSort(node)?.let {
            fields += inputValue(
                Constants.SORT,
                ListType(it.asType()) // TODO make required https://github.com/neo4j/graphql/issues/809
            ) {
                description("Specify one or more ${node.name}Sort objects to sort ${node.plural} by. The sorts will be applied in the order in which they are arranged in the array.".asDescription())
            }
        }
    } ?: throw IllegalStateException("at least the paging fields should be present")


    private fun addSort(node: Node) = getOrCreateInputObjectType("${node.name}Sort",
        init = { description("Fields to sort ${node.plural} by. The order in which sorts are applied is not guaranteed when specifying many fields in one ${node.name}Sort object.".asDescription()) },
        initFields = { fields, _ ->
            node.sortableFields.forEach { fields += inputValue(it.fieldName, Constants.Types.SortDirection) }
        }
    )
}
