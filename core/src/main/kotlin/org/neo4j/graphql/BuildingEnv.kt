package org.neo4j.graphql

import graphql.Scalars
import graphql.schema.*
import org.neo4j.graphql.handler.projection.ProjectionBase

class BuildingEnv(
        val types: MutableMap<String, GraphQLNamedType>,
        private val sourceSchema: GraphQLSchema
) {

    private val typesForRelation = types.values
        .filterIsInstance<GraphQLObjectType>()
        .filter { it.getDirective(DirectiveConstants.RELATION) != null }
        .map { it.getDirectiveArgument<String>(DirectiveConstants.RELATION, DirectiveConstants.RELATION_NAME, null)!! to it.name }
        .toMap()

    fun buildFieldDefinition(
            prefix: String,
            resultType: GraphQLOutputType,
            scalarFields: List<GraphQLFieldDefinition>,
            nullableResult: Boolean,
            forceOptionalProvider: (field: GraphQLFieldDefinition) -> Boolean = { false }
    ): GraphQLFieldDefinition.Builder {
        var type: GraphQLOutputType = resultType
        if (!nullableResult) {
            type = GraphQLNonNull(type)
        }
        return GraphQLFieldDefinition.newFieldDefinition()
            .name("$prefix${resultType.name()}")
            .arguments(getInputValueDefinitions(scalarFields, forceOptionalProvider))
            .type(type.ref() as GraphQLOutputType)
    }

    fun getInputValueDefinitions(
            relevantFields: List<GraphQLFieldDefinition>,
            forceOptionalProvider: (field: GraphQLFieldDefinition) -> Boolean): List<GraphQLArgument> {
        return relevantFields.map { field ->
            var type = field.type as GraphQLType
            type = getInputType(type)
            type = if (forceOptionalProvider(field)) {
                (type as? GraphQLNonNull)?.wrappedType ?: type
            } else {
                type
            }
            input(field.name, type)
        }
    }

    fun addQueryField(fieldDefinition: GraphQLFieldDefinition) {
        addOperation(sourceSchema.queryTypeName(), fieldDefinition)
    }

    fun addMutationField(fieldDefinition: GraphQLFieldDefinition) {
        addOperation(sourceSchema.mutationTypeName(), fieldDefinition)
    }

    /**
     * add the given operation to the corresponding rootType
     */
    private fun addOperation(rootTypeName: String, fieldDefinition: GraphQLFieldDefinition) {
        val rootType = types[rootTypeName]
        types[rootTypeName] = if (rootType == null) {
            val builder = GraphQLObjectType.newObject()
            builder.name(rootTypeName)
                .field(fieldDefinition)
                .build()
        } else {
            val existingRootType = (rootType as? GraphQLObjectType
                    ?: throw IllegalStateException("root type $rootTypeName is not an object type but ${rootType.javaClass}"))
            if (existingRootType.getFieldDefinition(fieldDefinition.name) != null) {
                return // definition already exists, we don't override it
            }
            existingRootType
                .transform { builder -> builder.field(fieldDefinition) }
        }
    }

    fun addFilterType(type: GraphQLFieldsContainer, createdTypes: MutableSet<String> = mutableSetOf()): String {
        val filterName = "_${type.name}Filter"
        if (createdTypes.contains(filterName)) {
            return filterName
        }
        val existingFilterType = types[filterName]
        if (existingFilterType != null) {
            return (existingFilterType as? GraphQLInputType)?.name()
                    ?: throw IllegalStateException("Filter type $filterName is already defined but not an input type")
        }
        createdTypes.add(filterName)
        val builder = GraphQLInputObjectType.newInputObject()
            .name(filterName)
        listOf("AND", "OR", "NOT").forEach {
            builder.field(GraphQLInputObjectField.newInputObjectField()
                .name(it)
                .type(GraphQLList(GraphQLNonNull(GraphQLTypeReference(filterName)))))
        }
        type.fieldDefinitions
            .filter { it.dynamicPrefix() == null } // TODO currently we do not support filtering on dynamic properties
            .forEach { field ->
                val typeDefinition = field.type.inner()
                val filterType = when {
                    typeDefinition.isNeo4jType() -> getInputType(typeDefinition).requiredName()
                    typeDefinition.isScalar() -> typeDefinition.innerName()
                    typeDefinition is GraphQLEnumType -> typeDefinition.innerName()
                    else -> addFilterType(getInnerFieldsContainer(typeDefinition), createdTypes)
                }

                if (field.isRelationship()) {
                    RelationOperator.createRelationFilterFields(type, field, filterType, builder)
                } else {
                    val graphQLType = types[filterType] ?: typeDefinition
                    FieldOperator.forType(graphQLType)
                        .forEach { op -> builder.addFilterField(op.fieldName(field.name), op.list, filterType, field.description) }
                    if (graphQLType.isNeo4jSpatialType()) {
                        val distanceFilterType = getSpatialDistanceFilter(graphQLType)
                        FieldOperator.forType(distanceFilterType)
                            .forEach { op -> builder.addFilterField(op.fieldName(field.name + NEO4j_POINT_DISTANCE_FILTER_SUFFIX), op.list, NEO4j_POINT_DISTANCE_FILTER) }
                    }
                }

            }
        types[filterName] = builder.build()
        return filterName
    }

    private fun getSpatialDistanceFilter(pointType: GraphQLType): GraphQLInputType {
        return addInputType(NEO4j_POINT_DISTANCE_FILTER, listOf(
                GraphQLFieldDefinition.newFieldDefinition().name("distance").type(GraphQLNonNull(Scalars.GraphQLFloat)).build(),
                GraphQLFieldDefinition.newFieldDefinition().name("point").type(GraphQLNonNull(pointType)).build()
        ))
    }

    fun addOptions(type: GraphQLFieldsContainer): String {
        val optionsName = "${type.name}Options"
        val optionsType = types[optionsName]
        if (optionsType != null) {
            return (optionsType as? GraphQLInputType)?.requiredName()
                    ?: throw IllegalStateException("Ordering type $type.name is already defined but not an input type")
        }
        val sortTypeName = addSortInputType(type)
        val optionsTypeBuilder =  GraphQLInputObjectType.newInputObject().name(optionsName)
        if (sortTypeName != null) {
            optionsTypeBuilder.field(GraphQLInputObjectField.newInputObjectField()
                .name(ProjectionBase.SORT)
                .type(GraphQLList(GraphQLNonNull(GraphQLTypeReference(sortTypeName))))
                .description("Specify one or more $sortTypeName objects to sort ${type.name}s by. The sorts will be applied in the order in which they are arranged in the array.")
                .build())
        }
        optionsTypeBuilder.field(GraphQLInputObjectField.newInputObjectField()
                .name(ProjectionBase.LIMIT)
                .type(Scalars.GraphQLInt)
                .description("Defines the maximum amount of records returned")
                .build())
            .field(GraphQLInputObjectField.newInputObjectField()
                .name(ProjectionBase.SKIP)
                .type(Scalars.GraphQLInt)
                .description("Defines the amount of records to be skipped")
                .build())
            .build()
        types[optionsName] = optionsTypeBuilder.build()
        return optionsName
    }

    private fun addSortInputType(type: GraphQLFieldsContainer): String? {
        val sortTypeName = "${type.name}Sort"
        val sortType = types[sortTypeName]
        if (sortType != null) {
            return (sortType as? GraphQLInputType)?.requiredName()
                    ?: throw IllegalStateException("Ordering type $type.name is already defined but not an input type")
        }
        val relevantFields = type.relevantFields()
        if (relevantFields.isEmpty()){
            return null
        }
        val builder = GraphQLInputObjectType.newInputObject()
            .name(sortTypeName)
            .description("Fields to sort ${type.name}s by. The order in which sorts are applied is not guaranteed when specifying many fields in one MovieSort object.")
        for (relevantField in relevantFields) {
            builder.field(GraphQLInputObjectField.newInputObjectField()
                .name(relevantField.name)
                .type(GraphQLTypeReference("SortDirection"))
                .build())
        }
        types[sortTypeName] = builder.build()
        return sortTypeName
    }

    fun addOrdering(type: GraphQLFieldsContainer): String? {
        val orderingName = "_${type.name}Ordering"
        var existingOrderingType = types[orderingName]
        if (existingOrderingType != null) {
            return (existingOrderingType as? GraphQLInputType)?.requiredName()
                    ?: throw IllegalStateException("Ordering type $type.name is already defined but not an input type")
        }
        val sortingFields = type.fieldDefinitions
            .filter { it.type.isScalar() || it.isNeo4jType() }
            .sortedByDescending { it.isID() }
        if (sortingFields.isEmpty()) {
            return null
        }
        existingOrderingType = GraphQLEnumType.newEnum()
            .name(orderingName)
            .values(sortingFields.flatMap { fd ->
                listOf("_asc", "_desc")
                    .map {
                        GraphQLEnumValueDefinition
                            .newEnumValueDefinition()
                            .name(fd.name + it)
                            .value(fd.name + it)
                            .build()
                    }
            })
            .build()
        types[orderingName] = existingOrderingType
        return orderingName
    }

    fun addInputType(inputName: String, relevantFields: List<GraphQLFieldDefinition>): GraphQLInputType {
        var inputType = types[inputName]
        if (inputType != null) {
            return inputType as? GraphQLInputType
                    ?: throw IllegalStateException("Filter type $inputName is already defined but not an input type")
        }
        inputType = getInputType(inputName, relevantFields)
        types[inputName] = inputType
        return inputType
    }

    fun getTypeForRelation(nameOfRelation: String): GraphQLObjectType? {
        return typesForRelation[nameOfRelation]?.let { types[it] } as? GraphQLObjectType
    }

    private fun getInputType(inputName: String, relevantFields: List<GraphQLFieldDefinition>): GraphQLInputObjectType {
        return GraphQLInputObjectType.newInputObject()
            .name(inputName)
            .fields(getInputValueDefinitions(relevantFields))
            .build()
    }

    private fun getInputValueDefinitions(relevantFields: List<GraphQLFieldDefinition>): List<GraphQLInputObjectField> {
        return relevantFields.map {
            // just make evrything optional
            val type = (it.type as? GraphQLNonNull)?.wrappedType ?: it.type
            GraphQLInputObjectField
                .newInputObjectField()
                .name(it.name)
                .description(it.description)
                .type(getInputType(type).ref() as GraphQLInputType)
                .build()
        }
    }

    private fun getInnerFieldsContainer(type: GraphQLType): GraphQLFieldsContainer {
        var innerType = type.inner()
        if (innerType is GraphQLTypeReference) {
            innerType = types[innerType.name]
                    ?: throw IllegalArgumentException("${innerType.name} is unknown")
        }
        return innerType as? GraphQLFieldsContainer
                ?: throw IllegalArgumentException("${innerType.name()} is neither an object nor an interface")
    }

    private fun getInputType(type: GraphQLType): GraphQLInputType {
        val inner = type.inner()
        if (inner is GraphQLInputType) {
            return type as GraphQLInputType
        }
        if (inner.isNeo4jType()) {
            return neo4jTypeDefinitions
                .find { it.typeDefinition == inner.name() }
                ?.let { types[it.inputDefinition] } as? GraphQLInputType
                    ?: throw IllegalArgumentException("Cannot find input type for ${inner.name()}")
        }
        return type as? GraphQLInputType
                ?: throw IllegalArgumentException("${type.name()} is not allowed for input")
    }

}
