package org.neo4j.graphql

import graphql.language.*
import graphql.language.TypeDefinition
import graphql.schema.DataFetcher
import graphql.schema.idl.ScalarInfo
import graphql.schema.idl.TypeDefinitionRegistry
import org.atteo.evo.inflector.English
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
            forceOptionalProvider: (field: FieldDefinition) -> Boolean): List<InputValueDefinition> {
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
                        input(op.fieldName(field.name), wrappedType)
                    }
            } else {
                listOf(input(field.name, type))
            }
        }
    }

    protected fun addQueryField(fieldDefinition: FieldDefinition) {
        addOperation(typeDefinitionRegistry.queryTypeName(), fieldDefinition)
    }

    protected fun addMutationField(fieldDefinition: FieldDefinition) {
        addOperation(typeDefinitionRegistry.mutationTypeName(), fieldDefinition)
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
            typeDefinitionRegistry.add(ObjectTypeDefinition.newObjectTypeDefinition()
                .name(rootTypeName)
                .fieldDefinition(fieldDefinition)
                .build())
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

    protected fun addFilterType(type: ImplementingTypeDefinition<*>, createdTypes: MutableSet<String> = mutableSetOf()): String {
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
            builder.inputValueDefinition(InputValueDefinition.newInputValueDefinition()
                .name(it)
                .type(ListType(NonNullType(TypeName(filterName))))
                .build())
        }
        type.fieldDefinitions
            .filterNot { it.isIgnored() }
            .filter { it.dynamicPrefix() == null } // TODO currently we do not support filtering on dynamic properties
            .forEach { field ->
                val typeDefinition = field.type.resolve()
                        ?: throw IllegalArgumentException("type ${field.type.name()} cannot be resolved")
                val filterType = when {
                    field.type.inner().isNeo4jType() -> getInputType(field.type).name()!!
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
                        .forEach { op -> builder.addFilterField(op.fieldName(field.name), op.list, filterType, field.description) }
                    if (typeDefinition.isNeo4jSpatialType()) {
                        val distanceFilterType = getSpatialDistanceFilter(neo4jTypeDefinitionRegistry.getUnwrappedType(filterType) as TypeDefinition<*>)
                        FieldOperator.forType(distanceFilterType, true)
                            .forEach { op -> builder.addFilterField(op.fieldName(field.name + NEO4j_POINT_DISTANCE_FILTER_SUFFIX), op.list, NEO4j_POINT_DISTANCE_FILTER) }
                    }
                }

            }
        typeDefinitionRegistry.add(builder.build())
        return filterName
    }

    private fun getSpatialDistanceFilter(pointType: TypeDefinition<*>): InputObjectTypeDefinition {
        return addInputType(NEO4j_POINT_DISTANCE_FILTER, listOf(
                input("distance", NonNullType(TypeFloat)),
                input("point", NonNullType(TypeName(pointType.name)))
        ))
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
            optionsTypeBuilder.inputValueDefinition(input(
                    ProjectionBase.SORT,
                    ListType(NonNullType(TypeName(sortTypeName))),
                    "Specify one or more $sortTypeName objects to sort ${English.plural(type.name)} by. The sorts will be applied in the order in which they are arranged in the array.")
            )
        }
        optionsTypeBuilder
            .inputValueDefinition(input(ProjectionBase.LIMIT, TypeInt, "Defines the maximum amount of records returned"))
            .inputValueDefinition(input(ProjectionBase.SKIP, TypeInt, "Defines the amount of records to be skipped"))
            .build()
        typeDefinitionRegistry.add(optionsTypeBuilder.build())
        return optionsName
    }

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

    private fun getTypeFromAnyRegistry(name: String?): TypeDefinition<*>? = typeDefinitionRegistry.getUnwrappedType(name)
            ?: neo4jTypeDefinitionRegistry.getUnwrappedType(name)

    fun ImplementingTypeDefinition<*>.relationship(): RelationshipInfo<ImplementingTypeDefinition<*>>? = RelationshipInfo.create(this, neo4jTypeDefinitionRegistry)

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

    fun TypeDefinitionRegistry.getUnwrappedType(name: String?): TypeDefinition<TypeDefinition<*>>? = getType(name)?.unwrap()

    fun DirectivesContainer<*>.cypherDirective(): CypherDirective? = if (hasDirective(DirectiveConstants.CYPHER)) {
        CypherDirective(
                getMandatoryDirectiveArgument(DirectiveConstants.CYPHER, DirectiveConstants.CYPHER_STATEMENT),
                getMandatoryDirectiveArgument(DirectiveConstants.CYPHER, DirectiveConstants.CYPHER_PASS_THROUGH, false)
        )
    } else {
        null
    }

    fun <T> DirectivesContainer<*>.getDirectiveArgument(directiveName: String, argumentName: String, defaultValue: T? = null): T? =
            getDirectiveArgument(neo4jTypeDefinitionRegistry, directiveName, argumentName, defaultValue)

    private fun <T> DirectivesContainer<*>.getMandatoryDirectiveArgument(directiveName: String, argumentName: String, defaultValue: T? = null): T =
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
}
