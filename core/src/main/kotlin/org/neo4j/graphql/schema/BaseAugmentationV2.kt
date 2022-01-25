package org.neo4j.graphql.schema

import graphql.language.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.directives.FullTextDirective
import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.schema.relations.InterfaceRelationFieldAugmentations
import org.neo4j.graphql.schema.relations.NodeRelationFieldAugmentations
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import org.neo4j.graphql.schema.relations.UnionRelationFieldAugmentations
import kotlin.reflect.KFunction1

open class BaseAugmentationV2(
    val ctx: AugmentationContext,
) : AugmentationBase by ctx {

    fun generateContainerCreateInputIT(node: Node) = generateContainerCreateInputIT(
        node.name,
        node.fields.filterIsInstance<RelationField>(),
        node.scalarFields,
        enforceFields = true,
    )

    fun generateContainerCreateInputIT(
        sourceName: String,
        relationFields: List<RelationField>,
        scalarFields: List<ScalarField>,
        enforceFields: Boolean = false,
    ) =
        getOrCreateRelationInputObjectType(
            sourceName,
            "CreateInput",
            relationFields,
            RelationFieldBaseAugmentation::generateFieldCreateIT,
            wrapList = false,
            scalarFields,
            enforceFields = enforceFields,
        )

    fun generateContainerUpdateIT(node: Node) = generateContainerUpdateIT(
        node.name,
        node.fields.filterIsInstance<RelationField>(),
        node.scalarFields,
        enforceFields = true,
    )

    fun generateContainerUpdateIT(
        sourceName: String,
        relationFields: List<RelationField>,
        scalarFields: List<ScalarField>,
        enforceFields: Boolean = false,
    ) = getOrCreateRelationInputObjectType(
        sourceName,
        "UpdateInput",
        relationFields,
        RelationFieldBaseAugmentation::generateFieldUpdateIT,
        scalarFields = scalarFields,
        update = true,
        enforceFields = enforceFields,
    )

    fun generateContainerConnectOrCreateInputIT(node: Node) =
        getOrCreateRelationInputObjectType(
            node.name,
            "ConnectOrCreateInput",
            node.relationFields,
            RelationFieldBaseAugmentation::generateFieldConnectOrCreateIT
        )

    fun generateContainerConnectInputIT(node: Node) = getOrCreateRelationInputObjectType(
        node.name,
        "ConnectInput",
        node.relationFields,
        RelationFieldBaseAugmentation::generateFieldConnectIT,
    )

    fun generateContainerDisconnectInputIT(node: Node) = getOrCreateRelationInputObjectType(
        node.name,
        "DisconnectInput",
        node.relationFields,
        RelationFieldBaseAugmentation::generateFieldDisconnectIT
    )

    fun generateContainerDeleteInputIT(node: Node) = getOrCreateRelationInputObjectType(
        node.name,
        "DeleteInput",
        node.relationFields,
        RelationFieldBaseAugmentation::generateFieldDeleteIT
    )

    fun generateContainerRelationCreateInputIT(node: Node) = getOrCreateRelationInputObjectType(
        node.name,
        "RelationInput",
        node.relationFields,
        RelationFieldBaseAugmentation::generateFieldRelationCreateIT
    )

    fun generateOptionsIT(node: Node) = getOrCreateInputObjectType("${node.name}Options") { fields, _ ->
        fields += inputValue(Constants.LIMIT, Constants.Types.Int)
        fields += inputValue(Constants.OFFSET, Constants.Types.Int)
        generateSortIT(node)?.let {
            fields += inputValue(
                Constants.SORT,
                ListType(it.asType()) // TODO make required https://github.com/neo4j/graphql/issues/809
            ) {
                description("Specify one or more ${node.name}Sort objects to sort ${node.plural} by. The sorts will be applied in the order in which they are arranged in the array.".asDescription())
            }
        }
    } ?: throw IllegalStateException("at least the paging fields should be present")


    private fun generateSortIT(node: Node) = getOrCreateInputObjectType("${node.name}Sort",
        init = { description("Fields to sort ${node.plural} by. The order in which sorts are applied is not guaranteed when specifying many fields in one ${node.name}Sort object.".asDescription()) },
        initFields = { fields, _ ->
            node.sortableFields.forEach { fields += inputValue(it.fieldName, Constants.Types.SortDirection) }
        }
    )

    fun generateFulltextIT(node: Node) = getOrCreateInputObjectType("${node.name}Fulltext") { fields, _ ->
        node.fulltextDirective?.indexes?.forEach { index ->
            generateFulltextIndexIT(node, index)?.let { fields += inputValue(index.name, it.asType()) }
        }
    }

    private fun generateFulltextIndexIT(node: Node, index: FullTextDirective.FullTextIndex) =
        getOrCreateInputObjectType("${node.name}${index.name.capitalize()}Fulltext") { fields, _ ->
            fields += inputValue(Constants.FULLTEXT_PHRASE, NonNullType(Constants.Types.String))
            // TODO normalize operation?
            fields += inputValue(Constants.FULLTEXT_SCORE + "_EQUAL", Constants.Types.Int)
        }


    fun generateWhereIT(field: RelationField): String? =
        getTypeFromRelationField(field, RelationFieldBaseAugmentation::generateFieldWhereIT)

    fun generateWhereIT(node: Node): String? =
        getOrCreateInputObjectType("${node.name}Where") { fields, _ ->
            fields += getWhereFields(node.name, node.fields)
        }

    protected fun generateConnectWhereIT(node: Node) =
        getOrCreateInputObjectType("${node.name}ConnectWhere") { fields, _ ->
            generateWhereIT(node)?.let { whereType ->
                fields += inputValue(Constants.NODE_FIELD, whereType.asRequiredType())
            }
        }

    protected fun generateRelationPropertiesWhereIT(properties: RelationshipProperties) =
        getOrCreateInputObjectType(properties.interfaceName + "Where") { fields, _ ->
            fields += getWhereFields(properties.interfaceName, properties.fields)
        }

    fun generateConnectionWhereIT(field: ConnectionField): String? =
        getTypeFromRelationField(
            field.relationshipField,
            RelationFieldBaseAugmentation::generateFieldConnectionWhereIT
        )

    protected fun generateConnectOrCreateWhereIT(node: Node) =
        getOrCreateInputObjectType("${node.name}ConnectOrCreateWhere") { fields, _ ->
            generateUniqueWhereIT(node)?.let {
                fields += inputValue(Constants.NODE_FIELD, it.asRequiredType())
            }
        }

    private fun generateUniqueWhereIT(node: Node) =
        getOrCreateInputObjectType("${node.name}UniqueWhere") { fields, _ ->
            node.uniqueFields.forEach { uniqueField ->
                val type = if (uniqueField.typeMeta.type.isList()) {
                    ListType(uniqueField.typeMeta.type.inner())
                } else {
                    uniqueField.typeMeta.type.name().asType()
                }
                fields += inputValue(uniqueField.fieldName, type)
            }
        }

    protected fun getWhereFields(
        typeName: String,
        fieldList: List<BaseField>,
        isInterface: Boolean = false,
    ): List<InputValueDefinition> {
        val result = mutableListOf<InputValueDefinition>()
        fieldList.forEach { field ->
            FieldOperator.forField(field, ctx.schemaConfig).forEach { op ->
                result += inputValue(
                    op.fieldName(field.fieldName, ctx.schemaConfig),
                    when {
                        op.listInput -> field.typeMeta.whereType.inner()
                        op.list -> ListType(field.typeMeta.whereType) // TODO make required inside list
                        else -> when {
                            op.distance && field.typeMeta.whereType.name() == Constants.POINT_INPUT_TYPE -> Constants.Types.PointDistance
                            op.distance && field.typeMeta.whereType.name() == Constants.CARTESIAN_POINT_INPUT_TYPE -> Constants.Types.CartesianPointDistance
                            else -> field.typeMeta.whereType
                        }
                    }
                )
            }
            if (field is RelationField) {
                if (field.node != null) { // TODO REVIEW Darrell why not for union or interfaces https://github.com/neo4j/graphql/issues/810
                    generateWhereIT(field)?.let {
                        result += inputValue(field.fieldName, it.asType())
                        result += inputValue(field.fieldName + "_NOT", it.asType())
                    }
                    generateAggregateInputIT(typeName, field).let {
                        result += inputValue(field.fieldName + "Aggregate", it.asType())
                    }
                }
            }
            if (field is ConnectionField) {
                generateConnectionWhereIT(field)?.let {
                    result += inputValue(field.fieldName, it.asType())
                    result += inputValue(field.fieldName + "_NOT", it.asType())

                }
            }
        }

        if (!isInterface && result.isNotEmpty()) {
            val type = ListType("${typeName}Where".asRequiredType())
            result += inputValue("OR", type)
            result += inputValue("AND", type)
        }
        return result
    }

    private fun generateAggregateInputIT(sourceName: String, rel: RelationField) =
        getOrCreateInputObjectType("${sourceName}${rel.fieldName.capitalize()}AggregateInput") { fields, name ->
            fields += inputValue(Constants.COUNT, Constants.Types.Int)
            fields += inputValue(Constants.COUNT + "_LT", Constants.Types.Int)
            fields += inputValue(Constants.COUNT + "_LTE", Constants.Types.Int)
            fields += inputValue(Constants.COUNT + "_GT", Constants.Types.Int)
            fields += inputValue(Constants.COUNT + "_GTE", Constants.Types.Int)
            generateWhereAggregationInputTypeForContainer(
                "${sourceName}${rel.fieldName.capitalize()}NodeAggregationWhereInput",
                rel.node?.fields
            )
                ?.let { fields += inputValue(Constants.NODE_FIELD, it.asType()) }
            generateWhereAggregationInputTypeForContainer(
                "${sourceName}${rel.fieldName.capitalize()}EdgeAggregationWhereInput",
                rel.properties?.fields
            )
                ?.let { fields += inputValue(Constants.EDGE_FIELD, it.asType()) }
            fields += inputValue("AND", ListType(name.asRequiredType()))
            fields += inputValue("OR", ListType(name.asRequiredType()))
        }
            ?: throw IllegalStateException("Expected at least the count field")

    private fun generateWhereAggregationInputTypeForContainer(name: String, relFields: List<BaseField>?) =
        getOrCreateInputObjectType(name) { fields, _ ->
            relFields
                ?.filterIsInstance<PrimitiveField>()
                ?.filter { Constants.WHERE_AGGREGATION_TYPES.contains(it.typeMeta.type.name()) }
                ?.forEach { field ->
                    when {
                        field.typeMeta.type.name() == "ID" -> listOf("EQUAL" to Constants.Types.ID)
                        field.typeMeta.type.name() == "String" -> Constants.WHERE_AGGREGATION_OPERATORS.flatMap { op ->
                            listOf(
                                op to if (op === "EQUAL") Constants.Types.String else Constants.Types.Int,
                                "AVERAGE_${op}" to Constants.Types.Float,
                                "LONGEST_${op}" to Constants.Types.Int,
                                "SHORTEST_${op}" to Constants.Types.Int,
                            )
                        }
                        Constants.WHERE_AGGREGATION_AVERAGE_TYPES.contains(field.typeMeta.type.name()) -> {
                            val averageType = when (field.typeMeta.type.name()) {
                                Constants.BIG_INT, Constants.DURATION -> field.typeMeta.type.inner()
                                else -> Constants.Types.Float
                            }
                            Constants.WHERE_AGGREGATION_OPERATORS.flatMap { op ->
                                listOf(
                                    op to field.typeMeta.type.inner(),
                                    "AVERAGE_${op}" to averageType,
                                    "MIN_${op}" to field.typeMeta.type.inner(),
                                    "MAX_${op}" to field.typeMeta.type.inner(),
                                ).let { result ->
                                    if (field.typeMeta.type.name() != Constants.DURATION) {
                                        result + ("SUM_${op}" to field.typeMeta.type.inner())
                                    } else {
                                        result
                                    }
                                }
                            }
                        }
                        else ->
                            Constants.WHERE_AGGREGATION_OPERATORS.flatMap { op ->
                                listOf(
                                    op to field.typeMeta.type.inner(),
                                    "MIN_${op}" to field.typeMeta.type.inner(),
                                    "MAX_${op}" to field.typeMeta.type.inner(),
                                )
                            }
                    }
                        .forEach { (suffix, type) ->
                            fields += inputValue(
                                field.fieldName + "_" + suffix,
                                type
                            )
                        }
                }

            if (fields.isNotEmpty()) {
                fields += inputValue("AND", ListType(name.asRequiredType()))
                fields += inputValue("OR", ListType(name.asRequiredType()))
            }
        }

    private fun getOrCreateRelationInputObjectType(
        sourceName: String,
        suffix: String,
        relationFields: List<RelationField>,
        extractor: KFunction1<RelationFieldBaseAugmentation, String?>,
        wrapList: Boolean = true,
        scalarFields: List<ScalarField> = emptyList(),
        update: Boolean = false,
        enforceFields: Boolean = false,
    ) = getOrCreateInputObjectType(sourceName + suffix) { fields, _ ->
        scalarFields
            .filterNot { it.generated || (update && it.readonly) }
            .forEach { field ->
                val type = if (update) {
                    field.typeMeta.updateType
                        ?: throw IllegalStateException("missing type on $sourceName.${field.fieldName} for update")
                } else {
                    field.typeMeta.createType
                        ?: throw IllegalStateException("missing type on $sourceName.${field.fieldName} for create")
                }
                fields += inputValue(field.fieldName, type) {
                    if (!update && field is PrimitiveField) {
                        defaultValue(field.defaultValue)
                    }
                }
            }
        relationFields
            .forEach { rel ->
                getTypeFromRelationField(rel, extractor)?.let { typeName ->
                    val type = if (!rel.isUnion && wrapList) {
                        // for union fields, the arrays are moved down one level, so we don't wrap them here
                        typeName.wrapType(rel)
                    } else {
                        typeName.asType()
                    }
                    fields += inputValue(rel.fieldName, type)
                }
            }
        if (fields.isEmpty() && enforceFields) {
            fields += inputValue(Constants.EMPTY_INPUT, Constants.Types.Boolean) {
                // TODO use a link of this project
                description("Appears because this input type would be empty otherwise because this type is composed of just generated and/or relationship properties. See https://neo4j.com/docs/graphql-manual/current/troubleshooting/faqs/".asDescription())
            }
        }
    }

    protected fun getTypeFromRelationField(
        rel: RelationField,
        extractor: KFunction1<RelationFieldBaseAugmentation, String?>
    ): String? {
        val aug = when {
            rel.isInterface -> InterfaceRelationFieldAugmentations(ctx, rel)
            rel.isUnion -> UnionRelationFieldAugmentations(ctx, rel)
            else -> NodeRelationFieldAugmentations(ctx, rel)
        }
        return extractor(aug)
    }

    fun generateNodeOT(node: Node) = getOrCreateObjectType(node.name,
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
                            generateAggregationSelectionOT(
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

    fun generateInterfaceType(interfaze: Interface) {
        ctx.typeDefinitionRegistry.replace(InterfaceTypeDefinition.newInterfaceTypeDefinition()
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

    private fun mapField(field: BaseField): FieldDefinition {
        val args = field.arguments.toMutableList()
        val type = when (field) {
            is ConnectionField -> generateConnectionOT(field).wrapLike(field.typeMeta.type)
            else -> field.typeMeta.type
        }
        if (field is RelationField) {
            generateWhereIT(field)?.let {
                args += inputValue(Constants.WHERE, it.asType())
            }
            val optionType = when {
                field.isInterface -> Constants.Types.QueryOptions
                field.isUnion -> Constants.Types.QueryOptions
                else -> generateOptionsIT(
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
            generateConnectionSortIT(field)
                ?.let { args += inputValue(Constants.SORT, ListType(it.asRequiredType())) }
        }
        return field(field.fieldName, type) {
            description(field.description)
            comments(field.comments)
            directives(field.otherDirectives)
            inputValueDefinitions(args)
        }
    }


    private fun generateConnectionOT(field: ConnectionField) =
        getOrCreateObjectType(field.typeMeta.type.name()) { fields, _ ->
            generateRelationshipOT(field).let {
                fields += field(Constants.EDGES_FIELD, NonNullType(ListType(it.asRequiredType())))
            }
            fields += field(Constants.TOTAL_COUNT, NonNullType(Constants.Types.Int))
            fields += field(Constants.PAGE_INFO, NonNullType(Constants.Types.PageInfo))
        }
            ?: throw IllegalStateException("Expected ${field.typeMeta.type.name()} to have fields")


    private fun generateRelationshipOT(field: ConnectionField): String =
        getOrCreateObjectType(field.relationshipTypeName,
            init = {
                field.properties?.let { implementz(it.interfaceName.asType()) }
                (field.owner as? Node)?.interfaces?.forEach { interfaze ->
                    interfaze.fields.filterIsInstance<ConnectionField>()
                        .find { it.fieldName == field.fieldName }
                        ?.let { generateRelationshipOT(it) }
                        ?.let { implementz(it.asType()) }
                }
            },
            initFields = { fields, _ ->
                fields += field(Constants.CURSOR_FIELD, Constants.Types.String.makeRequired())
                fields += field(Constants.NODE_FIELD, NonNullType(field.relationshipField.typeMeta.type.inner()))
                fields += field.properties?.fields?.map { mapField(it) } ?: emptyList()
            })
            ?: throw IllegalStateException("Expected ${field.relationshipTypeName} to have fields")


    private fun generateConnectionSortIT(field: ConnectionField) =
        getOrCreateInputObjectType(field.typeMeta.type.name() + "Sort") { fields, _ ->
            field.relationshipField.properties
                ?.let { generatePropertySortIT(it) }
                ?.let { fields += inputValue(Constants.EDGE_FIELD, it.asType()) }
            field.relationshipField.node
                ?.let { generateNodeSortIT(it) }
                ?.let { fields += inputValue(Constants.NODE_FIELD, it.asType()) }
        }

    private fun generatePropertySortIT(properties: RelationshipProperties) =
        getOrCreateInputObjectType(properties.interfaceName + "Sort") { fields, _ ->
            properties.fields.forEach {
                fields += inputValue(it.fieldName, Constants.Types.SortDirection)
            }
        }

    private fun generateNodeSortIT(node: Node) =
        getOrCreateInputObjectType(
            node.name + "Sort",
            init = { description("Fields to sort ${node.plural} by. The order in which sorts are applied is not guaranteed when specifying many fields in one ${node.name}Sort object.".asDescription()) },
            initFields = { fields, _ ->
                node.sortableFields.forEach {
                    fields += inputValue(it.fieldName, Constants.Types.SortDirection)
                }
            }
        )

    private fun generateAggregationSelectionOT(baseTypeName: String, refNode: Node, rel: RelationField) =
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
                    getAggregationSelectionLibraryType(field.typeMeta.type)
                        ?.let { fields += field(field.fieldName, NonNullType(it)) }
                }
        }

    protected fun getAggregationSelectionLibraryType(type: Type<*>): Type<*>? {
        val suffix =  if (type.isRequired()) "NonNullable" else "Nullable"
        val name = "${type.name()}AggregateSelection$suffix"
        ctx.neo4jTypeDefinitionRegistry.getUnwrappedType(name) ?: return null
        return TypeName(name)
    }
}
