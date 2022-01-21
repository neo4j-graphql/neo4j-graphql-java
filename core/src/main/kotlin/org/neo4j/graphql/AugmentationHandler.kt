package org.neo4j.graphql

import graphql.language.*
import graphql.language.TypeDefinition
import graphql.schema.DataFetcher
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeDefinitionRegistry
import org.atteo.evo.inflector.English
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.fields.PrimitiveField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.handler.projection.ProjectionBase

/**
 * A base class for augmenting a TypeDefinitionRegistry. There a re 2 steps in augmenting the types:
 * 1. augmenting the type by creating the relevant query / mutation fields and adding filtering and sorting to the relation fields
 * 2. generating a data fetcher based on a field definition. The field may be an augmented field (from step 1)
 * but can also be a user specified query / mutation field
 */
abstract class AugmentationHandler(
    val schemaConfig: SchemaConfig,
    val typeDefinitionRegistry: TypeDefinitionRegistry,
    val neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
) {

    private val currentlyCreating = mutableSetOf<String>()

    enum class OperationType {
        QUERY,
        MUTATION
    }

    /**
     * The 1st step in enhancing a schema. This method creates relevant query / mutation fields and / or adds filtering and sorting to the relation fields of the given type
     * @param type the type for which the schema should be enhanced / augmented
     */
    open fun augmentType(type: ImplementingTypeDefinition<*>) {}

    /**
     * The 2nd step is creating a data fetcher based on a field definition. The field may be an augmented field (from step 1)
     * but can also be a user specified query / mutation field
     * @param operationType the type of the field
     * @param fieldDefinition the filed to create the data fetcher for
     * @return a data fetcher for the field or null if not applicable
     */
    abstract fun createDataFetcher(operationType: OperationType, fieldDefinition: FieldDefinition): DataFetcher<Cypher>?

    protected fun buildFieldDefinition(
        prefix: String,
        resultType: ImplementingTypeDefinition<*>,
        scalarFields: List<FieldDefinition>,
        nullableResult: Boolean,
        forceOptionalProvider: (field: FieldDefinition) -> Boolean = { false }
    ): FieldDefinition.Builder {
        var type: Type<*> = TypeName(resultType.name)
        if (!nullableResult) {
            type = NonNullType(type)
        }
        return FieldDefinition.newFieldDefinition()
            .name("$prefix${resultType.name}")
            .inputValueDefinitions(getInputValueDefinitions(scalarFields, false, forceOptionalProvider))
            .type(type)
    }

    protected fun getInputValueDefinitions(
        relevantFields: List<FieldDefinition>,
        addFieldOperations: Boolean,
        forceOptionalProvider: (field: FieldDefinition) -> Boolean
    ): List<InputValueDefinition> {
        return relevantFields.flatMap { field ->
            var type = getInputType(field.type)
            type = if (forceOptionalProvider(field)) {
                (type as? NonNullType)?.type ?: type
            } else {
                type
            }
            if (addFieldOperations && !field.isNativeId()) {
                val typeDefinition = field.type.resolve()
                    ?: throw IllegalArgumentException("type ${field.type.name()} cannot be resolved")
                FieldOperator.forType(typeDefinition, field.type.inner().isNeo4jType())
                    .map { op ->
                        val wrappedType: Type<*> = when {
                            op.list -> ListType(NonNullType(TypeName(type.name())))
                            else -> type
                        }
                        input(op.fieldName(field.name, schemaConfig), wrappedType)
                    }
            } else {
                listOf(input(field.name, type))
            }
        }
    }

    protected fun addQueryField(fieldDefinition: FieldDefinition) {
        addOperation(typeDefinitionRegistry.queryTypeName(), fieldDefinition)
    }

    protected fun addQueryField(name: String, type: Type<*>, args: ((MutableList<InputValueDefinition>) -> Unit)?) {
        val argList = mutableListOf<InputValueDefinition>()
        args?.invoke(argList)
        addQueryField(field(name, type, argList))
    }

    protected fun addMutationField(fieldDefinition: FieldDefinition) {
        addOperation(typeDefinitionRegistry.mutationTypeName(), fieldDefinition)
    }

    protected fun addMutationField(name: String, type: Type<*>, args: ((MutableList<InputValueDefinition>) -> Unit)?) {
        val argList = mutableListOf<InputValueDefinition>()
        args?.invoke(argList)
        addMutationField(field(name, type, argList))
    }

    protected fun isRootType(type: ImplementingTypeDefinition<*>): Boolean {
        return type.name == typeDefinitionRegistry.queryTypeName()
                || type.name == typeDefinitionRegistry.mutationTypeName()
                || type.name == typeDefinitionRegistry.subscriptionTypeName()
    }

    /**
     * add the given operation to the corresponding rootType
     */
    private fun addOperation(rootTypeName: String, fieldDefinition: FieldDefinition) {
        val rootType = typeDefinitionRegistry.getType(rootTypeName)?.unwrap()
        if (rootType == null) {
            typeDefinitionRegistry.add(
                ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(rootTypeName)
                    .fieldDefinition(fieldDefinition)
                    .build()
            )
        } else {
            val existingRootType = (rootType as? ObjectTypeDefinition
                ?: throw IllegalStateException("root type $rootTypeName is not an object type but ${rootType.javaClass}"))
            if (existingRootType.fieldDefinitions.find { it.name == fieldDefinition.name } != null) {
                return // definition already exists, we don't override it
            }
            typeDefinitionRegistry.remove(rootType)
            typeDefinitionRegistry.add(rootType.transform { it.fieldDefinition(fieldDefinition) })
        }
    }

    protected fun addFilterType(
        type: ImplementingTypeDefinition<*>,
        createdTypes: MutableSet<String> = mutableSetOf()
    ): String {
        val filterName = if (schemaConfig.useWhereFilter) type.name + "Where" else "_${type.name}Filter"
        if (createdTypes.contains(filterName)) {
            return filterName
        }
        val existingFilterType = typeDefinitionRegistry.getType(filterName).unwrap()
        if (existingFilterType != null) {
            return (existingFilterType as? InputObjectTypeDefinition)?.name
                ?: throw IllegalStateException("Filter type $filterName is already defined but not an input type")
        }
        createdTypes.add(filterName)
        val builder = InputObjectTypeDefinition.newInputObjectDefinition()
            .name(filterName)
        listOf("AND", "OR", "NOT").forEach {
            builder.inputValueDefinition(
                InputValueDefinition.newInputValueDefinition()
                    .name(it)
                    .type(ListType(NonNullType(TypeName(filterName))))
                    .build()
            )
        }
        type.fieldDefinitions
            .filterNot { it.isIgnored() }
            .filter { it.dynamicPrefix() == null } // TODO currently we do not support filtering on dynamic properties
            .forEach { field ->
                val typeDefinition = field.type.resolve()
                    ?: throw IllegalArgumentException("type ${field.type.name()} cannot be resolved")
                val filterType = when {
                    field.type.inner().isNeo4jType() -> getInputType(field.type).name()
                    typeDefinition is ScalarTypeDefinition -> typeDefinition.name
                    typeDefinition is EnumTypeDefinition -> typeDefinition.name
                    typeDefinition is ImplementingTypeDefinition -> {
                        when {
                            field.type.inner().isNeo4jType() -> typeDefinition.name
                            else -> addFilterType(typeDefinition, createdTypes)
                        }
                    }
                    else -> throw IllegalArgumentException("${field.type.name()} is neither an object nor an interface")
                }

                if (field.isRelationship()) {
                    RelationOperator.createRelationFilterFields(type, field, filterType, builder)
                } else {
                    FieldOperator.forType(typeDefinition, field.type.inner().isNeo4jType())
                        .forEach { op ->
                            builder.addFilterField(
                                op.fieldName(field.name, schemaConfig),
                                op.list,
                                filterType,
                                field.description
                            )
                        }
                    if (typeDefinition.isNeo4jSpatialType()) {
                        val distanceFilterType =
                            getSpatialDistanceFilter(neo4jTypeDefinitionRegistry.getUnwrappedType(filterType) as TypeDefinition<*>)
                        FieldOperator.forType(distanceFilterType, true)
                            .forEach { op ->
                                builder.addFilterField(
                                    op.fieldName(field.name + NEO4j_POINT_DISTANCE_FILTER_SUFFIX, schemaConfig),
                                    op.list,
                                    NEO4j_POINT_DISTANCE_FILTER
                                )
                            }
                    }
                }

            }
        typeDefinitionRegistry.add(builder.build())
        return filterName
    }

    fun addAggregationSelectionType(node: Node): String {
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

    fun addFulltextQueryType(node: Node) = node.fulltextDirective?.indexes?.let { indexes ->
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

    fun addUniqueQueryType(node: Node): String? = node.uniqueFields
        .map {
            val type = if (it.typeMeta.type.isList()) {
                ListType(TypeName(it.typeMeta.type.name()))
            } else {
                TypeName(it.typeMeta.type.name())
            }
            inputValue(it.fieldName, type)
        }
        .takeIf { it.isNotEmpty() }
        ?.let { addInputObjectType("${node.name}UniqueWhere", it) }


    fun getOrCreateAggregationType(type: Type<*>): Type<*>? {
        val name = "${type.name()}AggregateSelection"
        // TODO can we copy over later?
        typeDefinitionRegistry.getUnwrappedType(name)
            ?: neo4jTypeDefinitionRegistry.getUnwrappedType(name)?.also { typeDefinitionRegistry.add(it) }
            ?: return null
        return TypeName(name)
    }

    private fun getSpatialDistanceFilter(pointType: TypeDefinition<*>): InputObjectTypeDefinition {
        return addInputType(
            NEO4j_POINT_DISTANCE_FILTER, listOf(
                input("distance", NonNullType(TypeFloat)),
                input("point", NonNullType(TypeName(pointType.name)))
            )
        )
    }

    protected fun addOptions(type: ImplementingTypeDefinition<*>): String {
        val optionsName = "${type.name}Options"
        val optionsType = typeDefinitionRegistry.getType(optionsName)?.unwrap()
        if (optionsType != null) {
            return (optionsType as? InputObjectTypeDefinition)?.name
                ?: throw IllegalStateException("Ordering type $type.name is already defined but not an input type")
        }
        val sortTypeName = addSortInputType(type)
        val optionsTypeBuilder = InputObjectTypeDefinition.newInputObjectDefinition().name(optionsName)
        if (sortTypeName != null) {
            optionsTypeBuilder.inputValueDefinition(
                input(
                    ProjectionBase.SORT,
                    ListType(NonNullType(TypeName(sortTypeName))),
                    "Specify one or more $sortTypeName objects to sort ${English.plural(type.name)} by. The sorts will be applied in the order in which they are arranged in the array."
                )
            )
        }
        optionsTypeBuilder
            .inputValueDefinition(
                input(
                    ProjectionBase.LIMIT,
                    TypeInt,
                    "Defines the maximum amount of records returned"
                )
            )
            .inputValueDefinition(input(ProjectionBase.SKIP, TypeInt, "Defines the amount of records to be skipped"))
            .build()
        typeDefinitionRegistry.add(optionsTypeBuilder.build())
        return optionsName
    }

    protected fun addOptions(node: Node) = getOrCreateInputObjectType("${node.name}Options") { fields, _ ->
        fields += inputValue(Constants.LIMIT, Constants.Types.Int)
        fields += inputValue(Constants.OFFSET, Constants.Types.Int)
        addSort(node)?.let {
            fields += inputValue(Constants.SORT,
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

    private fun addSortInputType(type: ImplementingTypeDefinition<*>): String? {
        val sortTypeName = "${type.name}Sort"
        val sortType = typeDefinitionRegistry.getType(sortTypeName)?.unwrap()
        if (sortType != null) {
            return (sortType as? InputObjectTypeDefinition)?.name
                ?: throw IllegalStateException("Ordering type $type.name is already defined but not an input type")
        }
        val relevantFields = type.getScalarFields()
        if (relevantFields.isEmpty()) {
            return null
        }
        val builder = InputObjectTypeDefinition.newInputObjectDefinition()
            .name(sortTypeName)
            .description("Fields to sort ${type.name}s by. The order in which sorts are applied is not guaranteed when specifying many fields in one MovieSort object.".asDescription())
        for (relevantField in relevantFields) {
            builder.inputValueDefinition(input(relevantField.name, TypeName("SortDirection")))
        }
        typeDefinitionRegistry.add(builder.build())
        return sortTypeName
    }

    protected fun addOrdering(type: ImplementingTypeDefinition<*>): String? {
        val orderingName = "_${type.name}Ordering"
        var existingOrderingType = typeDefinitionRegistry.getType(orderingName)?.unwrap()
        if (existingOrderingType != null) {
            return (existingOrderingType as? EnumTypeDefinition)?.name
                ?: throw IllegalStateException("Ordering type $type.name is already defined but not an input type")
        }
        val sortingFields = type.getScalarFields()
        if (sortingFields.isEmpty()) {
            return null
        }
        existingOrderingType = EnumTypeDefinition.newEnumTypeDefinition()
            .name(orderingName)
            .enumValueDefinitions(sortingFields.flatMap { fd ->
                listOf("_asc", "_desc")
                    .map {
                        EnumValueDefinition
                            .newEnumValueDefinition()
                            .name(fd.name + it)
                            .build()
                    }
            })
            .build()
        typeDefinitionRegistry.add(existingOrderingType)
        return orderingName
    }

    private fun addInputType(inputName: String, relevantFields: List<InputValueDefinition>): InputObjectTypeDefinition {
        var inputType = typeDefinitionRegistry.getType(inputName)?.unwrap()
        if (inputType != null) {
            return inputType as? InputObjectTypeDefinition
                ?: throw IllegalStateException("Filter type $inputName is already defined but not an input type")
        }
        inputType = getInputType(inputName, relevantFields)
        typeDefinitionRegistry.add(inputType)
        return inputType
    }

    private fun getInputType(inputName: String, relevantFields: List<InputValueDefinition>): InputObjectTypeDefinition {
        return InputObjectTypeDefinition.newInputObjectDefinition()
            .name(inputName)
            .inputValueDefinitions(relevantFields)
            .build()
    }

    private fun getInputType(type: Type<*>): Type<*> {
        if (type.inner().isNeo4jType()) {
            return neo4jTypeDefinitions
                .find { it.typeDefinition == type.name() }
                ?.let { TypeName(it.inputDefinition) }
                ?: throw IllegalArgumentException("Cannot find input type for ${type.name()}")
        }
        return type
    }

    private fun getTypeFromAnyRegistry(name: String?): TypeDefinition<*>? =
        typeDefinitionRegistry.getUnwrappedType(name)
            ?: neo4jTypeDefinitionRegistry.getUnwrappedType(name)

    fun ImplementingTypeDefinition<*>.relationship(): RelationshipInfo<ImplementingTypeDefinition<*>>? =
        RelationshipInfo.create(this, neo4jTypeDefinitionRegistry)

    fun ImplementingTypeDefinition<*>.getScalarFields(): List<FieldDefinition> = fieldDefinitions
        .filterNot { it.isIgnored() }
        .filter { it.type.inner().isScalar() || it.type.inner().isNeo4jType() }
        .sortedByDescending { it.type.inner().isID() }

    fun ImplementingTypeDefinition<*>.getFieldDefinition(name: String) = this.fieldDefinitions
        .filterNot { it.isIgnored() }
        .find { it.name == name }

    fun ImplementingTypeDefinition<*>.getIdField() = this.fieldDefinitions
        .filterNot { it.isIgnored() }
        .find { it.type.inner().isID() }

    fun Type<*>.resolve(): TypeDefinition<*>? = getTypeFromAnyRegistry(name())
    fun Type<*>.isScalar(): Boolean = resolve() is ScalarTypeDefinition
    private fun Type<*>.isNeo4jType(): Boolean = name()
        ?.takeIf {
            !ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.containsKey(it)
                    && it.startsWith("_Neo4j") // TODO remove this check by refactoring neo4j input types
        }
        ?.let { neo4jTypeDefinitionRegistry.getUnwrappedType(it) } != null

    fun Type<*>.isID(): Boolean = name() == "ID"


    fun FieldDefinition.isNativeId(): Boolean = name == ProjectionBase.NATIVE_ID
    fun FieldDefinition.dynamicPrefix(): String? =
        getDirectiveArgument(DirectiveConstants.DYNAMIC, DirectiveConstants.DYNAMIC_PREFIX, null)

    fun FieldDefinition.isRelationship(): Boolean =
        !type.inner().isNeo4jType() && type.resolve() is ImplementingTypeDefinition<*>

    fun TypeDefinitionRegistry.getUnwrappedType(name: String?): TypeDefinition<TypeDefinition<*>>? =
        getType(name)?.unwrap()

    fun DirectivesContainer<*>.cypherDirective(): CypherDirective? = if (hasDirective(DirectiveConstants.CYPHER)) {
        CypherDirective(
            getMandatoryDirectiveArgument(DirectiveConstants.CYPHER, DirectiveConstants.CYPHER_STATEMENT),
            getMandatoryDirectiveArgument(DirectiveConstants.CYPHER, DirectiveConstants.CYPHER_PASS_THROUGH, false)
        )
    } else {
        null
    }

    fun <T> DirectivesContainer<*>.getDirectiveArgument(
        directiveName: String,
        argumentName: String,
        defaultValue: T? = null
    ): T? =
        getDirectiveArgument(neo4jTypeDefinitionRegistry, directiveName, argumentName, defaultValue)

    private fun <T> DirectivesContainer<*>.getMandatoryDirectiveArgument(
        directiveName: String,
        argumentName: String,
        defaultValue: T? = null
    ): T =
        getDirectiveArgument(directiveName, argumentName, defaultValue)
            ?: throw IllegalStateException("No default value for @${directiveName}::$argumentName")

    fun input(name: String, type: Type<*>, description: String? = null): InputValueDefinition {
        val input = InputValueDefinition
            .newInputValueDefinition()
            .name(name)
            .type(type)
        if (description != null) {
            input.description(description.asDescription())
        }
        return input
            .build()
    }

    fun addInputObjectType(
        name: String,
        vararg fields: InputValueDefinition,
        init: (InputObjectTypeDefinition.Builder.() -> Unit)? = null
    ): String = addInputObjectType(name, fields.asList(), init)

    fun getOrCreateInputObjectType(
        name: String,
        init: (InputObjectTypeDefinition.Builder.() -> Unit)? = null,
        initFields: (fields: MutableList<InputValueDefinition>, name: String) -> Unit
    ): String? {
        if (currentlyCreating.contains(name)) {
            return name
        }
        val type = typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(name)
        return when (type != null) {
            true -> type
            else -> {
                val fields = mutableListOf<InputValueDefinition>()
                currentlyCreating.add(name)
                initFields(fields, name)
                currentlyCreating.remove(name)
                if (fields.isNotEmpty()) {
                    inputObjectType(name, fields, init).also { typeDefinitionRegistry.add(it) }
                } else {
                    null
                }
            }
        }?.name
    }

    fun addInputObjectType(
        name: String,
        fields: List<InputValueDefinition>,
        init: (InputObjectTypeDefinition.Builder.() -> Unit)? = null
    ): String {
        val type = typeDefinitionRegistry.getTypeByName(name)
            ?: inputObjectType(name, fields, init).also { typeDefinitionRegistry.add(it) }
        return type.name
    }

    fun addObjectType(
        name: String,
        vararg fields: FieldDefinition,
        init: (ObjectTypeDefinition.Builder.() -> Unit)? = null
    ): String = addObjectType(name, fields.asList(), init)

    fun addObjectType(
        name: String,
        fields: List<FieldDefinition>,
        init: (ObjectTypeDefinition.Builder.() -> Unit)? = null
    ): String {
        val type = typeDefinitionRegistry.getTypeByName(name)
            ?: objectType(name, fields, init).also { typeDefinitionRegistry.add(it) }
        return type.name
    }

    fun getOrCreateObjectType(
        name: String,
        init: (ObjectTypeDefinition.Builder.() -> Unit)? = null,
        initFields: (fields: MutableList<FieldDefinition>, name: String) -> Unit,
    ): String? {
        val type = typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(name)
        return when (type != null) {
            true -> type
            else -> {
                val fields = mutableListOf<FieldDefinition>()
                initFields(fields, name)
                if (fields.isNotEmpty()) {
                    objectType(name, fields, init).also { typeDefinitionRegistry.add(it) }
                } else {
                    null
                }
            }
        }?.name
    }

    fun getOrCreateInterfaceType(
        name: String,
        init: (InterfaceTypeDefinition.Builder.() -> Unit)? = null,
        initFields: (fields: MutableList<FieldDefinition>, name: String) -> Unit,
    ): String? {
        val type = typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(name)
        return when (type != null) {
            true -> type
            else -> {
                val fields = mutableListOf<FieldDefinition>()
                initFields(fields, name)
                if (fields.isNotEmpty()) {
                    interfaceType(name, fields, init).also { typeDefinitionRegistry.add(it) }
                } else {
                    null
                }
            }
        }?.name
    }

    companion object {
        fun objectType(init: ObjectTypeDefinition.Builder.() -> Unit): ObjectTypeDefinition {
            val type = ObjectTypeDefinition.newObjectTypeDefinition()
            type.init()
            return type.build()
        }

        fun inputObjectType(
            name: String,
            vararg fields: InputValueDefinition,
            init: (InputObjectTypeDefinition.Builder.() -> Unit)? = null
        ): InputObjectTypeDefinition = inputObjectType(name, fields.asList(), init)

        fun inputObjectType(
            name: String,
            fields: List<InputValueDefinition>,
            init: (InputObjectTypeDefinition.Builder.() -> Unit)? = null
        ): InputObjectTypeDefinition {
            val type = InputObjectTypeDefinition.newInputObjectDefinition()
            type.name(name)
            type.inputValueDefinitions(fields)
            init?.let { type.it() }
            return type.build()
        }

        fun inputObjectType(
            template: InputObjectTypeDefinition,
            init: InputObjectTypeDefinition.Builder.() -> Unit
        ): InputObjectTypeDefinition {
            return template.transform(init)
        }

        fun objectType(
            name: String,
            vararg fields: FieldDefinition,
            init: (ObjectTypeDefinition.Builder.() -> Unit)? = null
        ): ObjectTypeDefinition = objectType(name, fields.asList(), init)

        fun objectType(
            name: String,
            fields: List<FieldDefinition>,
            init: (ObjectTypeDefinition.Builder.() -> Unit)? = null
        ): ObjectTypeDefinition {
            val type = ObjectTypeDefinition.newObjectTypeDefinition()
            type.name(name)
            type.fieldDefinitions(fields)
            init?.let { type.it() }
            return type.build()
        }

        fun interfaceType(
            name: String,
            fields: List<FieldDefinition>,
            init: (InterfaceTypeDefinition.Builder.() -> Unit)? = null
        ): InterfaceTypeDefinition {
            val type = InterfaceTypeDefinition.newInterfaceTypeDefinition()
            type.name(name)
            type.definitions(fields)
            init?.let { type.it() }
            return type.build()
        }

        fun objectType(
            template: ObjectTypeDefinition,
            init: ObjectTypeDefinition.Builder.() -> Unit
        ): ObjectTypeDefinition {
            return template.transform(init)
        }


        fun inputValue(
            name: String,
            type: Type<*>,
            init: (InputValueDefinition.Builder.() -> Unit)? = null
        ): InputValueDefinition {
            val input = InputValueDefinition.newInputValueDefinition()
            input.name(name)
            input.type(type)
            init?.let { input.it() }
            return input.build()
        }

        fun field(
            name: String,
            type: Type<*>,
            args: List<InputValueDefinition> = emptyList(),
            init: (FieldDefinition.Builder.() -> Unit)? = null
        ): FieldDefinition {
            val field = FieldDefinition.newFieldDefinition()
            field.name(name)
            field.type(type)
            field.inputValueDefinitions(args)
            init?.let { field.it() }
            return field.build()
        }

        fun inputValue(
            template: InputValueDefinition,
            init: InputValueDefinition.Builder.() -> Unit
        ): InputValueDefinition {
            return template.transform(init)
        }

        fun ObjectTypeDefinition.Builder.description(text: String) = description(text.asDescription())

        fun ObjectTypeDefinition.Builder.field(init: FieldDefinition.Builder.() -> Unit): FieldDefinition {
            val type = FieldDefinition.newFieldDefinition()
            type.init()
            val build = type.build()
            this.fieldDefinition(build)
            return build
        }

        fun String.wrapType(rel: RelationField) = when {
            rel.typeMeta.type.isList() -> ListType(this.asRequiredType())
            else -> this.asType()
        }

        fun NodeDirectivesBuilder.addNonLibDirectives(directivesContainer: DirectivesContainer<*>) {
            this.directives(directivesContainer.directives.filterNot { DirectiveConstants.LIB_DIRECTIVES.contains(it.name) })
        }
    }
}

