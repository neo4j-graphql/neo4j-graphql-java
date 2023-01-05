package org.neo4j.graphql.schema

import graphql.language.*
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.ImplementingType
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

    fun generateOptionsIT(implementingType: ImplementingType) =
        getOrCreateInputObjectType("${implementingType.name}Options") { fields, _ ->
            fields += inputValue(Constants.LIMIT, Constants.Types.Int)
            fields += inputValue(Constants.OFFSET, Constants.Types.Int)
            generateSortIT(implementingType)?.let {
                fields += inputValue(
                    Constants.SORT,
                    ListType(it.asRequiredType())
                ) {
                    description("Specify one or more ${implementingType.name}Sort objects to sort ${implementingType.pascalCasePlural} by. The sorts will be applied in the order in which they are arranged in the array.".asDescription())
                }
            }
        } ?: throw IllegalStateException("at least the paging fields should be present")


    fun generateSortIT(implementingType: ImplementingType) =
        getOrCreateInputObjectType("${implementingType.name}Sort",
            init = { description("Fields to sort ${implementingType.pascalCasePlural} by. The order in which sorts are applied is not guaranteed when specifying many fields in one ${implementingType.name}Sort object.".asDescription()) },
            initFields = { fields, _ ->
                implementingType.sortableFields.forEach { field ->
                    fields += inputValue(
                        field.fieldName,
                        Constants.Types.SortDirection
                    ) {
                        directives(field.deprecatedDirectives)
                    }
                }
            }
        )

    private fun generateFulltextIndexIT(node: Node, index: FullTextDirective.FullTextIndex) =
        getOrCreateInputObjectType("${node.name}${index.indexName.capitalize()}Fulltext") { fields, _ ->
            fields += inputValue(Constants.FULLTEXT_PHRASE, NonNullType(Constants.Types.String))
            // TODO normalize operation?
            fields += inputValue(Constants.FULLTEXT_SCORE_EQUAL, Constants.Types.Int)
        }


    fun generateWhereOfFieldIT(field: RelationField): String? =
        getTypeFromRelationField(field, RelationFieldBaseAugmentation::generateFieldWhereIT)

    fun generateWhereIT(node: Node): String? =
        getOrCreateInputObjectType("${node.name}Where") { fields, _ ->
            fields += getWhereFields(node.name, node.fields, plural = node.pascalCasePlural)
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
        plural: String = typeName
    ): List<InputValueDefinition> {
        val result = mutableListOf<InputValueDefinition>()
        fieldList.forEach { field ->
            if (field is ScalarField) {
                field.predicates.values.forEach {
                    result += inputValue(it.name, it.type) {
                        directives(field.deprecatedDirectives)
                    }
                }
            }
            if (field is RelationField) {
                if (field.node != null) { // TODO REVIEW Darrell why not for union or interfaces https://github.com/neo4j/graphql/issues/810
                    generateWhereOfFieldIT(field)?.let {
                        if (field.typeMeta.type.isList()) {
                            // n..m relationship
                            listOf("ALL", "NONE", "SINGLE", "SOME").forEach { filter ->
                                result += inputValue("${field.fieldName}_$filter", it.asType()) {
                                    description(
                                        "Return $plural where ${if (filter !== "SINGLE") filter.lowercase() else "one"} of the related ${field.getImplementingType()?.pascalCasePlural} match this filter".asDescription()
                                    )
                                    directives(field.deprecatedDirectives)
                                }
                            }
                            // TODO remove
                            result += inputValue(field.fieldName, it.asType()) {
                                directive(
                                    Directive(
                                        "deprecated",
                                        listOf(
                                            Argument(
                                                "reason",
                                                StringValue("Use `${field.fieldName}_SOME` instead.")
                                            )
                                        )
                                    )
                                )
                            }
                            result += inputValue(
                                field.fieldName + "_NOT",
                                it.asType()
                            ) {
                                directive(
                                    Directive(
                                        "deprecated",
                                        listOf(
                                            Argument(
                                                "reason",
                                                StringValue("Use `${field.fieldName}_NONE` instead.")
                                            )
                                        )
                                    )
                                )
                            }
                        } else {
                            // n..1 relationship
                            result += inputValue(field.fieldName, it.asType())
                            result += inputValue(field.fieldName + "_NOT", it.asType())
                        }

                    }
                    generateAggregateInputIT(typeName, field).let {
                        result += inputValue(field.fieldName + Constants.AGGREGATION_FIELD_SUFFIX, it.asType()) {
                            directives(field.deprecatedDirectives)
                        }
                    }
                }
            }
            if (field is ConnectionField) {
                generateConnectionWhereIT(field)?.let {
                    if (field.relationshipField.typeMeta.type.isList()) {
                        // n..m relationship
                        listOf("ALL", "NONE", "SINGLE", "SOME").forEach { filter ->
                            result += inputValue("${field.fieldName}_$filter", it.asType()) {
                                directives(field.deprecatedDirectives)
                            }
                        }
                        // TODO remove
                        result += inputValue(field.fieldName, it.asType()) {
                            directive(
                                Directive(
                                    "deprecated",
                                    listOf(Argument("reason", StringValue("Use `${field.fieldName}_SOME` instead.")))
                                )
                            )
                        }
                        result += inputValue(field.fieldName + "_NOT", it.asType()) {
                            directive(
                                Directive(
                                    "deprecated",
                                    listOf(Argument("reason", StringValue("Use `${field.fieldName}_NONE` instead.")))
                                )
                            )
                        }
                    } else {
                        // n..1 relationship
                        result += inputValue(field.fieldName, it.asType())
                        result += inputValue(field.fieldName + "_NOT", it.asType())
                    }
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
            fields += inputValue(Constants.AND, ListType(name.asRequiredType()))
            fields += inputValue(Constants.OR, ListType(name.asRequiredType()))
        }
            ?: throw IllegalStateException("Expected at least the count field")

    private fun generateWhereAggregationInputTypeForContainer(name: String, relFields: List<BaseField>?) =
        getOrCreateInputObjectType(name) { fields, _ ->
            relFields
                ?.filterIsInstance<PrimitiveField>()
                ?.flatMap { it.aggregationPredicates.entries }
                ?.forEach { (name, def) ->
                    fields += inputValue(name, def.type) {
                        directives(def.field.deprecatedDirectives)
                    }
                }

            if (fields.isNotEmpty()) {
                fields += inputValue("AND", ListType(name.asRequiredType()))
                fields += inputValue("OR", ListType(name.asRequiredType()))
            }
        }

    protected fun addScalarFields(
        fields: MutableList<InputValueDefinition>,
        sourceName: String,
        scalarFields: List<ScalarField>,
        update: Boolean
    ) {
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
                    if (!update && field is HasDefaultValue) {
                        defaultValue(field.defaultValue)
                    }
                    directives(field.deprecatedDirectives)
                }
                if (update) {
                    if (field.typeMeta.type.isList()
                        &&
                        (field is PrimitiveField // TODO remove after https://github.com/neo4j/graphql/issues/2677
                                || field is PointField)
                    ) {
                        fields += inputValue("${field.fieldName}_${Constants.ArrayOperations.POP}", Constants.Types.Int)
                        fields += inputValue("${field.fieldName}_${Constants.ArrayOperations.PUSH}", type)
                    } else {
                        when (field.typeMeta.type.name()) {
                            Constants.INT, Constants.BIG_INT -> listOf(
                                Constants.Math.INCREMENT,
                                Constants.Math.DECREMENT
                            ).forEach {
                                fields += inputValue("${field.fieldName}_$it", type)
                            }

                            Constants.FLOAT -> listOf(
                                Constants.Math.ADD,
                                Constants.Math.SUBTRACT,
                                Constants.Math.DIVIDE,
                                Constants.Math.MULTIPLY
                            ).forEach {
                                fields += inputValue("${field.fieldName}_$it", type)
                            }
                        }
                    }
                }
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
        addScalarFields(fields, sourceName, scalarFields, update)
        relationFields
            .forEach { rel ->
                getTypeFromRelationField(rel, extractor)?.let { typeName ->
                    val type = if (!rel.isUnion && wrapList) {
                        // for union fields, the arrays are moved down one level, so we don't wrap them here
                        typeName.wrapType(rel)
                    } else {
                        typeName.asType()
                    }
                    fields += inputValue(rel.fieldName, type) { directives(rel.deprecatedDirectives) }

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
                    (field as? RelationField)?.let { n ->
                        generateAggregationSelectionOT(field)?.let { aggr ->
                            fields += field(field.fieldName + Constants.AGGREGATION_FIELD_SUFFIX, aggr.asType()) {
                                generateWhereOfFieldIT(field)
                                    ?.let { inputValueDefinition(inputValue(Constants.WHERE, it.asType())) }
                                directedArgument(field)?.let { inputValueDefinition(it) }
                                directives(field.deprecatedDirectives)
                            }
                        }

                    }
                }
        }
    )

    fun generateNodeConnectionOT(node: Node): String {
        val name = "${node.plural.capitalize()}Connection"
        return getOrCreateObjectType(name)
        { fields, _ ->
            generateNodeEdgeOT(node).let {
                fields += field(Constants.EDGES_FIELD, NonNullType(ListType(it.asRequiredType())))
            }
            fields += field(Constants.TOTAL_COUNT, NonNullType(Constants.Types.Int))
            fields += field(Constants.PAGE_INFO, NonNullType(Constants.Types.PageInfo))
        }
            ?: throw IllegalStateException("Expected $name to have fields")
    }

    private fun generateNodeEdgeOT(node: Node): String =
        getOrCreateObjectType("${node.name}Edge") { fields, _ ->
            fields += field(Constants.CURSOR_FIELD, Constants.Types.String.makeRequired())
            fields += field(Constants.NODE_FIELD, node.name.asType(true))
        }
            ?: throw IllegalStateException("Expected ${node.name}Edge to have fields")

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

    fun generateNodeFulltextOT(node: Node) = getOrCreateObjectType("${node.name}FulltextResult", {
        description("The result of a fulltext search on an index of ${node.name}".asDescription())
    }) { fields, _ ->
        fields += field(node.name.lowercase(), node.name.asType(true))
        fields += field(Constants.SCORE, Constants.Types.Float.makeRequired())
    }

    fun generateFulltextSort(node: Node) = getOrCreateInputObjectType("${node.name}FulltextSort", {
        description("The input for sorting a fulltext query on an index of ${node.name}".asDescription())
    }) { fields, _ ->
        generateSortIT(node)?.let { fields += inputValue(node.name.lowercase(), it.asType()) }
        fields += inputValue(Constants.SCORE, Constants.Types.SortDirection)
    }
        ?: throw IllegalStateException("Expected ${node.name}FulltextSort to have fields")

    fun generateFulltextWhere(node: Node) = getOrCreateInputObjectType("${node.name}FulltextWhere", {
        description("The input for filtering a fulltext query on an index of ${node.name}".asDescription())
    }) { fields, _ ->
        generateWhereIT(node)?.let { fields += inputValue(node.name.lowercase(), it.asType()) }
        fields += inputValue(Constants.SCORE, Constants.Types.FloatWhere)
    }
        ?: throw IllegalStateException("Expected ${node.name}FulltextWhere to have fields")


    private fun mapField(field: BaseField): FieldDefinition {
        val args = field.arguments.toMutableList()
        val type = when (field) {
            is ConnectionField -> generateConnectionOT(field).wrapLike(field.typeMeta.type)
            else -> field.typeMeta.type
        }
        if (field is RelationField) {
            generateWhereOfFieldIT(field)?.let {
                args += inputValue(Constants.WHERE, it.asType())
            }
            val optionType = field.extractOnTarget(
                onImplementingType = { generateOptionsIT(it).asType() },
                onUnion = { Constants.Types.QueryOptions },
            )
            args += inputValue(Constants.OPTIONS, optionType)
            directedArgument(field)?.let { args += it }
        }
        if (field is ConnectionField) {
            generateConnectionWhereIT(field)
                ?.let { args += inputValue(Constants.WHERE, it.asType()) }
            args += inputValue(Constants.FIRST, Constants.Types.Int)
            args += inputValue(Constants.AFTER, Constants.Types.String)
            directedArgument(field.relationshipField)?.let { args += it }
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

    private fun directedArgument(relationshipField: RelationField): InputValueDefinition? =
        when (relationshipField.queryDirection) {
            RelationField.QueryDirection.DEFAULT_DIRECTED -> true
            RelationField.QueryDirection.DEFAULT_UNDIRECTED -> false
            else -> null
        }?.let { defaultVal ->
            inputValue(Constants.DIRECTED, Constants.Types.Boolean) { defaultValue(BooleanValue(defaultVal)) }
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
            field.relationshipField.getImplementingType()
                ?.let { generateSortIT(it) }
                ?.let { fields += inputValue(Constants.NODE_FIELD, it.asType()) }
        }

    private fun generatePropertySortIT(properties: RelationshipProperties) =
        getOrCreateInputObjectType(properties.interfaceName + "Sort") { fields, _ ->
            properties.fields.forEach {
                fields += inputValue(it.fieldName, Constants.Types.SortDirection)
            }
        }

    private fun generateAggregationSelectionOT(rel: RelationField): String? {
        val refNode = rel.node ?: return null
        val aggregateTypeNames = rel.aggregateTypeNames ?: return null
        return getOrCreateObjectType(aggregateTypeNames.field) { fields, _ ->
            fields += field(Constants.COUNT, Constants.Types.Int.makeRequired())
            createAggregationField(aggregateTypeNames.node, refNode.fields)
                ?.let { fields += field(Constants.NODE_FIELD, it.asType()) }
            createAggregationField(aggregateTypeNames.edge, rel.properties?.fields ?: emptyList())
                ?.let { fields += field(Constants.EDGE_FIELD, it.asType()) }
        } ?: throw IllegalStateException("Expected at least the count field")
    }

    private fun createAggregationField(name: String, relFields: List<BaseField>) =
        getOrCreateObjectType(name) { fields, _ ->
            relFields
                .filterIsInstance<PrimitiveField>()
                .filterNot { it.typeMeta.type.isList() }
                .forEach { field ->
                    getAggregationSelectionLibraryType(field)
                        ?.let { fields += field(field.fieldName, NonNullType(it)) }
                }
        }

    protected fun getAggregationSelectionLibraryType(field: PrimitiveField): Type<*>? {
        val name = field.getAggregationSelectionLibraryTypeName()
        ctx.neo4jTypeDefinitionRegistry.getUnwrappedType(name) ?: return null
        return TypeName(name)
    }
}
