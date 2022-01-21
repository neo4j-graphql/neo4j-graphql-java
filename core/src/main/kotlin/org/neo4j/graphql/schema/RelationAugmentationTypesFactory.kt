package org.neo4j.graphql.schema

import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.ListType
import graphql.schema.DataFetcher
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.*
import kotlin.reflect.KFunction1

class RelationAugmentationTypesFactory(
    schemaConfig: SchemaConfig,
    typeDefinitionRegistry: TypeDefinitionRegistry,
    neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
) : AugmentationHandler(schemaConfig, typeDefinitionRegistry, neo4jTypeDefinitionRegistry) {

    fun addWhereType(node: Node): String? =
        getOrCreateInputObjectType("${node.name}Where") { fields, _ ->
            fields += getWhereFields(node.name, node.fields)
        }


    fun getWhereFields(
        typeName: String,
        fieldList: List<BaseField>,
        isInterface: Boolean = false,
    ): List<InputValueDefinition> {
        val result = mutableListOf<InputValueDefinition>()
        fieldList.forEach { field ->
            FieldOperator.forField(field, schemaConfig).forEach { op ->
                result += inputValue(
                    op.fieldName(field.fieldName, schemaConfig),
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
                    addWhereType(field)?.let {
                        result += inputValue(field.fieldName, it.asType())
                        result += inputValue(field.fieldName + "_NOT", it.asType())
                    }
                    getOrCreateAggregateInput(typeName, field).let {
                        result += inputValue(field.fieldName + "Aggregate", it.asType())
                    }
                }
            }
            if (field is ConnectionField) {
                addConnectionWhereType(field)?.let {
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

    private fun getOrCreateAggregateInput(sourceName: String, rel: RelationField) =
        getOrCreateInputObjectType("${sourceName}${rel.fieldName.capitalize()}AggregateInput") { fields, name ->
            fields += inputValue(Constants.COUNT, Constants.Types.Int)
            fields += inputValue(Constants.COUNT + "_LT", Constants.Types.Int)
            fields += inputValue(Constants.COUNT + "_LTE", Constants.Types.Int)
            fields += inputValue(Constants.COUNT + "_GT", Constants.Types.Int)
            fields += inputValue(Constants.COUNT + "_GTE", Constants.Types.Int)
            getOrCreateWhereAggregationInput(
                "${sourceName}${rel.fieldName.capitalize()}NodeAggregationWhereInput",
                rel.node?.fields
            )
                ?.let { fields += inputValue(Constants.NODE_FIELD, it.asType()) }
            getOrCreateWhereAggregationInput(
                "${sourceName}${rel.fieldName.capitalize()}EdgeAggregationWhereInput",
                rel.properties?.fields
            )
                ?.let { fields += inputValue(Constants.EDGE_FIELD, it.asType()) }
            fields += inputValue("AND", ListType(name.asRequiredType()))
            fields += inputValue("OR", ListType(name.asRequiredType()))
        }
            ?: throw IllegalStateException("Expected at least the count field")

    private fun getOrCreateWhereAggregationInput(name: String, relFields: List<BaseField>?) =
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
                        .forEach { (suffix, type) -> fields += inputValue(field.fieldName + "_" + suffix, type) }
                }

            if (fields.isNotEmpty()) {
                fields += inputValue("AND", ListType(name.asRequiredType()))
                fields += inputValue("OR", ListType(name.asRequiredType()))
            }
        }

    fun addCreateInputType(node: Node) = addCreateInputType(
        node.name,
        node.fields.filterIsInstance<RelationField>(),
        node.scalarFields,
        enforceFields = true,
    )

    fun addCreateInputType(
        sourceName: String,
        relationFields: List<RelationField>,
        scalarFields: List<ScalarField>,
        enforceFields: Boolean = false,
        ) =
        getOrCreateRelationInputObjectType(
            sourceName,
            "CreateInput",
            relationFields,
            RelationAugmentation::addCreateType,
            wrapList = false,
            scalarFields,
            enforceFields = enforceFields,
        )

    fun addRelationConnectInputField(sourceName: String, relationFields: List<RelationField>) =
        getOrCreateRelationInputObjectType(
            sourceName,
            "ConnectInput",
            relationFields,
            RelationAugmentation::addConnectType,
        )

    fun addRelationDeleteInputField(sourceName: String, relationFields: List<RelationField>) =
        getOrCreateRelationInputObjectType(
            sourceName,
            "DeleteInput",
            relationFields,
            RelationAugmentation::addDeleteType
        )

    fun addRelationDisconnectInputField(sourceName: String, relationFields: List<RelationField>) =
        getOrCreateRelationInputObjectType(
            sourceName,
            "DisconnectInput",
            relationFields,
            RelationAugmentation::addDisconnectType
        )

    fun addRelationInputField(sourceName: String, relationFields: List<RelationField>) =
        getOrCreateRelationInputObjectType(
            sourceName,
            "RelationInput",
            relationFields,
            RelationAugmentation::addRelationType
        )

    fun addWhereType(field: RelationField) =
        getTypeFromRelationField(field, RelationAugmentation::addWhereType)

    fun addConnectionWhereType(field: ConnectionField) =
        getTypeFromRelationField(field.relationshipField, RelationAugmentation::addConnectionWhereType)

    fun addUpdateInputType(node: Node) = addUpdateInputType(
        node.name,
        node.fields.filterIsInstance<RelationField>(),
        node.scalarFields,
        enforceFields = true,
    )

    fun addUpdateInputType(
        sourceName: String,
        relationFields: List<RelationField>,
        scalarFields: List<ScalarField>,
        enforceFields: Boolean = false,
        ) = getOrCreateRelationInputObjectType(
        sourceName,
        "UpdateInput",
        relationFields,
        RelationAugmentation::addUpdateType,
        scalarFields = scalarFields,
        update = true,
        enforceFields = enforceFields,
    )

    fun addRelationConnectOrCreateInputField(sourceName: String, relationFields: List<RelationField>): String? {
        return getOrCreateRelationInputObjectType(
            sourceName,
            "ConnectOrCreateInput",
            relationFields,
            RelationAugmentation::addConnectOrCreate
        )
    }

    private fun getOrCreateRelationInputObjectType(
        sourceName: String,
        suffix: String,
        relationFields: List<RelationField>,
        extractor: KFunction1<RelationAugmentation, String?>,
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

    private fun getTypeFromRelationField(
        rel: RelationField,
        extractor: KFunction1<RelationAugmentation, String?>
    ): String? {
        val aug = when {
            rel.isInterface -> InterfaceAugmentations(rel)
            rel.isUnion -> UnionAugmentations(rel)
            else -> NodeAugmentations(rel)
        }
        return extractor(aug)
    }

    private abstract inner class RelationAugmentation(
        rel: RelationField,
        private val isArray: Boolean = rel.typeMeta.type.isList(),
        val properties: RelationshipProperties? = rel.properties,
    ) {
        abstract fun addCreateType(): String?
        abstract fun addConnectType(): String?
        abstract fun addDeleteType(): String?
        abstract fun addDisconnectType(): String?
        abstract fun addRelationType(): String?
        abstract fun addUpdateType(): String?
        abstract fun addWhereType(): String?
        abstract fun addConnectionWhereType(): String?

        open fun addConnectOrCreate(): String? = null

        fun String.wrapType() = when {
            isArray -> ListType(this.asRequiredType())
            else -> this.asType()
        }

        fun addConnectOrCreate(parentPrefix: String, node: Node): String? {
            if (node.uniqueFields.isEmpty()) {
                return null
            }
            return getOrCreateInputObjectType("${parentPrefix}ConnectOrCreateFieldInput") { fields, name ->
                createWhereITC(node)?.let { fields += inputValue(Constants.WHERE, it.asRequiredType()) }
                createNodeWithEdgeCreateInputType("${name}OnCreate", node)?.let {
                    fields += inputValue(Constants.ON_CREATE_FIELD, it.asRequiredType())
                }
            }
        }

        private fun createWhereITC(node: Node) =
            getOrCreateInputObjectType("${node.name}ConnectOrCreateWhere") { fields, _ ->
                createUniqueWhere(node)?.let { fields += inputValue(Constants.NODE_FIELD, it.asRequiredType()) }
            }

        private fun createUniqueWhere(node: Node) =
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

        private fun createNodeWithEdgeCreateInputType(name: String, node: Node) =
            getOrCreateInputObjectType(name) { fields, _ ->
                addCreateInputType(node)?.let {
                    fields += inputValue(Constants.NODE_FIELD, it.asRequiredType())
                }
                addEdgePropertyCreateInputField(fields) { it.hasRequiredNonGeneratedFields }
            }

        protected fun addEdgePropertyCreateInputField(
            fields: MutableList<InputValueDefinition>,
            required: (RelationshipProperties) -> Boolean = { false }
        ) =
            properties?.let { props ->
                addCreateInputType(props.interfaceName, emptyList(), props.fields)?.let {
                    fields += inputValue(Constants.EDGE_FIELD, it.asType(required(props)))
                }
            }

        protected fun addEdgePropertyUpdateInputField(fields: MutableList<InputValueDefinition>) =
            properties?.let { props ->
                addUpdateInputType(props.interfaceName, emptyList(), props.fields)?.let {
                    fields += inputValue(Constants.EDGE_FIELD, it.asType())
                }
            }

        protected fun addRelationPropertyWhereType(properties: RelationshipProperties) =
            getOrCreateInputObjectType(properties.interfaceName + "Where") { fields, _ ->
                fields += getWhereFields(properties.interfaceName, properties.fields)
            }

        protected fun addNodeFieldInput(prefix: String, node: Node) =
            getOrCreateInputObjectType("${prefix}FieldInput") { fields, _ ->
                addCreateFieldInput(prefix, node)?.let {
                    fields += inputValue(Constants.CREATE_FIELD, it.wrapType())
                }
                addConnectFieldInput(prefix, node)?.let {
                    fields += inputValue(Constants.CONNECT_FIELD, it.wrapType())
                }
                addConnectOrCreate(prefix, node)?.let {
                    fields += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.wrapType())
                }
            }

        protected fun addCreateFieldInput(prefix: String, node: Node) =
            createNodeWithEdgeCreateInputType(prefix + "CreateFieldInput", node)

        protected fun addConnectFieldInput(prefix: String, node: Node) =
            getOrCreateInputObjectType(prefix + "ConnectFieldInput") { fields, _ ->
                addConnectWhere(node)?.let { fields += inputValue(Constants.WHERE, it.asType()) }
                addConnectInput(node)?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType()) }
                addEdgePropertyCreateInputField(fields) { it.hasRequiredNonGeneratedFields }
            }

        protected fun addUpdateFieldInput(prefix: String, node: Node) =
            getOrCreateInputObjectType(prefix + "UpdateFieldInput") { fields, _ ->
                addConnectionWhere(prefix, node)
                    ?.let { fields += inputValue(Constants.WHERE, it.asType()) }
                addUpdateConnectionInput(prefix, node)
                    ?.let { fields += inputValue(Constants.UPDATE_FIELD, it.asType()) }
                addConnectFieldInput(prefix, node)
                    ?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType()) }
                addDisconnectFieldInput(prefix, node)
                    ?.let { fields += inputValue(Constants.DISCONNECT_FIELD, it.wrapType()) }
                addCreateFieldInput(prefix, node)
                    ?.let { fields += inputValue(Constants.CREATE_FIELD, it.wrapType()) }
                addDeleteFieldInput(prefix, node)
                    ?.let { fields += inputValue(Constants.DELETE_FIELD, it.wrapType()) }
                addConnectOrCreate(prefix, node)
                    ?.let { fields += inputValue(Constants.CONNECT_OR_CREATE_FIELD, it.wrapType()) }
            }

        private fun addConnectWhere(node: Node) =
            getOrCreateInputObjectType("${node.name}ConnectWhere") { fields, _ ->
                addWhereType(node)?.let { whereType ->
                    fields += inputValue(Constants.NODE_FIELD, whereType.asRequiredType())
                }
            }

        protected fun addConnectInput(node: Node) = addRelationConnectInputField(node.name, node.relationFields)

        private fun addUpdateConnectionInput(prefix: String, node: Node) =
            getOrCreateInputObjectType(prefix + "UpdateConnectionInput") { fields, _ ->
                addUpdateInputType(node)?.let {
                    fields += inputValue(Constants.NODE_FIELD, it.asType())
                }
                addEdgePropertyUpdateInputField(fields)
            }

        protected fun addDisconnectFieldInput(node: Node) =
            addRelationDisconnectInputField(node.name, node.relationFields)


        protected fun addDisconnectFieldInput(prefix: String, node: Node) =
            getOrCreateInputObjectType(prefix + "DisconnectFieldInput") { fields, _ ->
                addConnectionWhere(prefix, node)?.let { fields += inputValue(Constants.WHERE, it.asType()) }
                addDisconnectFieldInput(node)?.let { fields += inputValue(Constants.DISCONNECT_FIELD, it.asType()) }
            }

        fun addConnectionWhere(prefix: String, node: Node, nameOverride: String = "${prefix}ConnectionWhere") =
            getOrCreateInputObjectType(nameOverride) { fields, name ->
                addWhereType(node)?.let {
                    fields += inputValue(Constants.NODE_FIELD, it.asType())
                    fields += inputValue(Constants.NODE_FIELD + "_NOT", it.asType())
                }
                properties?.let { addRelationPropertyWhereType(it) }?.let {
                    fields += inputValue(Constants.EDGE_FIELD, it.asType())
                    fields += inputValue(Constants.EDGE_FIELD + "_NOT", it.asType())
                }
                if (fields.isNotEmpty()) {
                    val listWhereType = ListType(name.asRequiredType())
                    fields += inputValue("AND", listWhereType)
                    fields += inputValue("OR", listWhereType)
                }
            }

        protected fun addDeleteFieldInput(node: Node) = addRelationDeleteInputField(node.name, node.relationFields)

        protected fun addDeleteFieldInput(prefix: String, node: Node) =
            getOrCreateInputObjectType(prefix + "DeleteFieldInput") { fields, _ ->
                addConnectionWhere(prefix, node)?.let { fields += inputValue(Constants.WHERE, it.asType()) }
                addDeleteFieldInput(node)?.let { fields += inputValue(Constants.DELETE_FIELD, it.asType()) }
            }
    }

    /**
     * Augmentation for relations referencing an interface
     */
    private inner class InterfaceAugmentations(
        private val rel: RelationField,
        private val interfaze: Interface = rel.interfaze
            ?: throw IllegalArgumentException("The type of ${rel.getOwnerName()}.${rel.fieldName} is expected to be an interface")
    ) :
        RelationAugmentation(rel) {

        private val prefix: String = rel.getOwnerName() + rel.fieldName.capitalize()

        override fun addCreateType() = getOrCreateInputObjectType("${prefix}FieldInput") { fields, _ ->
            addRelationType()?.let { fields += inputValue(Constants.CREATE_FIELD, it.wrapType()) }
            addConnectType()?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType()) }
        }

        override fun addConnectType() = getOrCreateInputObjectType("${prefix}ConnectFieldInput") { fields, _ ->
            addInterfaceField("ConnectInput", ::addConnectInput, RelationAugmentation::addConnectType)
                ?.let { fields += inputValue(Constants.CONNECT_FIELD, it.asType()) }
            addEdgePropertyCreateInputField(fields, required = { it.hasRequiredFields })
            addConnectWhereInputType()?.let { fields += inputValue(Constants.WHERE, it.asType()) }
        }

        override fun addDeleteType() = getOrCreateInputObjectType("${prefix}DeleteFieldInput") { fields, _ ->
            addInterfaceField("DeleteInput", ::addDeleteFieldInput, RelationAugmentation::addDeleteType)
                ?.let { fields += inputValue(Constants.DELETE_FIELD, it.asType()) }
            addConnectionWhereInputType()?.let { fields += inputValue(Constants.WHERE, it.asType()) }
        }

        override fun addDisconnectType() = getOrCreateInputObjectType("${prefix}DisconnectFieldInput") { fields, _ ->
            addInterfaceField("DisconnectInput", ::addDisconnectFieldInput, RelationAugmentation::addDisconnectType)
                ?.let { fields += inputValue(Constants.DISCONNECT_FIELD, it.asType()) }
            addConnectionWhereInputType()?.let { fields += inputValue(Constants.WHERE, it.asType()) }
        }

        override fun addRelationType() = getOrCreateInputObjectType("${prefix}CreateFieldInput") { fields, _ ->
            addInterfaceCreateInputType()?.let { fields += inputValue(Constants.NODE_FIELD, it.asRequiredType()) }
            addEdgePropertyCreateInputField(fields, required = { true })
        }

        override fun addUpdateType() =
            getOrCreateInputObjectType("${prefix}UpdateFieldInput") { fields, _ ->
                addConnectType()?.let { fields += inputValue(Constants.CONNECT_FIELD, it.wrapType()) }
                addRelationType()?.let { fields += inputValue(Constants.CREATE_FIELD, it.wrapType()) }
                addDeleteType()?.let { fields += inputValue(Constants.DELETE_FIELD, it.wrapType()) }
                addDisconnectType()?.let { fields += inputValue(Constants.DISCONNECT_FIELD, it.wrapType()) }
                addUpdateConnectionInputType()?.let { fields += inputValue(Constants.UPDATE_FIELD, it.asType()) }
                addConnectionWhereInputType()?.let { fields += inputValue(Constants.WHERE, it.asType()) }
            }

        override fun addWhereType(): String? = getOrCreateInputObjectType("${interfaze.name}Where") { fields, _ ->
            addOnField("ImplementationsWhere", fields, asList = false, ::addWhereType)
            fields += getWhereFields(interfaze.name, interfaze.fields, isInterface = true)
        }

        private fun addUpdateConnectionInputType() =
            getOrCreateInputObjectType("${prefix}UpdateConnectionInput") { fields, _ ->
                addInterfaceUpdateInputType()?.let { fields += inputValue(Constants.NODE_FIELD, it.asType()) }
                addEdgePropertyUpdateInputField(fields)
            }

        private fun addInterfaceUpdateInputType() = addInterfaceField(
            "UpdateInput",
            ::addUpdateInputType,
            RelationAugmentation::addUpdateType,
            asList = false
        ) {
            interfaze.scalarFields.filterNot { it.generated || it.readonly }
                .mapNotNull { field -> field.typeMeta.updateType?.let { inputValue(field.fieldName, it) } }
        }

        private fun addInterfaceCreateInputType() =
            addImplementationDelegate("CreateInput", asList = false, ::addCreateInputType) {
                // TODO REVIEW Darrell
                //    interface-relationships_--nested-relationships.adoc
                //  vs
                //   interface-relationships_--nested-interface-relationships.adoc
//                interfaze.relationFields.mapNotNull { r ->
//                    getTypeFromRelationField(interfaze.name, r, RelationAugmentation::addCreateType)
//                        ?.let { inputValue(r.fieldName, it.asType()) }
//                }
                emptyList()
            }

        private fun addInterfaceField(
            suffix: String,
            implementationResolver: (Node) -> String?,
            relationFieldsResolver: KFunction1<RelationAugmentation, String?>,
            asList: Boolean = true,
            getAdditionalFields: (() -> List<InputValueDefinition>)? = null,
        ) = getOrCreateInputObjectType("${interfaze.name}$suffix") { fields, _ ->
            addOnField("Implementations$suffix", fields, asList, implementationResolver)
            interfaze.relationFields.forEach { r ->
                getTypeFromRelationField(r, relationFieldsResolver)
                    ?.let { fields += inputValue(r.fieldName, it.wrapType(r)) }
            }
            getAdditionalFields?.invoke()?.let { fields += it }
        }

        private fun addOnField(
            inputTypeSuffix: String,
            fields: MutableList<InputValueDefinition>,
            asList: Boolean,
            getNodeType: (Node) -> String?
        ) {
            addImplementationDelegate(inputTypeSuffix, asList = asList, getNodeType)?.let {
                fields += inputValue("_on", it.asType())
            }
        }

        private fun addImplementationDelegate(
            inputTypeSuffix: String,
            asList: Boolean,
            getNodeType: (Node) -> String?,
            getAdditionalFields: (() -> List<InputValueDefinition>)? = null,
        ) =
            getOrCreateInputObjectType("${interfaze.name}$inputTypeSuffix") { fields, _ ->
                interfaze.implementations.forEach { node ->
                    getNodeType(node)?.let {
                        val type = when (asList) {
                            true -> ListType(it.asRequiredType())
                            else -> it.asType()
                        }
                        fields += inputValue(node.name, type)
                    }
                }
                getAdditionalFields?.invoke()?.let { fields += it }
            }


        private fun addConnectWhereInputType() =
            getOrCreateInputObjectType("${rel.typeMeta.type.name()}ConnectWhere") { fields, _ ->
                addWhereType()?.let { whereType ->
                    fields += inputValue(Constants.NODE_FIELD, whereType.asRequiredType())
                }
            }


        // TODO merge with super.addConnectionWhere(prefix: String, node: Node)
        private fun addConnectionWhereInputType() =
            getOrCreateInputObjectType(rel.connectionField.typeMeta.whereType.name()) { fields, connectionWhereName ->
                addWhereType()?.let {
                    fields += inputValue(Constants.NODE_FIELD, it.asType())
                    fields += inputValue(Constants.NODE_FIELD + "_NOT", it.asType())
                }
                properties?.let { addRelationPropertyWhereType(it) }?.let {
                    fields += inputValue(Constants.EDGE_FIELD, it.asType())
                    fields += inputValue(Constants.EDGE_FIELD + "_NOT", it.asType())
                }
                if (fields.isNotEmpty()) {
                    val listWhereType = ListType(connectionWhereName.asRequiredType())
                    fields += inputValue("AND", listWhereType)
                    fields += inputValue("OR", listWhereType)
                }
            }

        override fun addConnectionWhereType() = addConnectionWhereInputType()

    }

    /**
     * Augmentation for relations referencing a union
     */
    private inner class UnionAugmentations(
        private val rel: RelationField,
        private val prefix: String = rel.getOwnerName() + rel.fieldName.capitalize()
    ) : RelationAugmentation(rel) {

        init {
            if (!rel.isUnion) {
                throw IllegalArgumentException("The type of ${rel.getOwnerName()}.${rel.fieldName} is expected to be an union")
            }
        }

        private fun Node.unionPrefix() = prefix + this.name

        override fun addCreateType() = getOrCreateInputObjectType("${prefix}CreateInput") { fields, _ ->
            rel.unionNodes.forEach { node ->
                addNodeFieldInput(node.unionPrefix(), node)?.let { fields += inputValue(node.name, it.asType()) }
            }
        }

        override fun addConnectType() = getOrCreateInputObjectType("${prefix}ConnectInput") { fields, _ ->
            rel.unionNodes.forEach { node ->
                addConnectFieldInput(node.unionPrefix(), node)?.let { fields += inputValue(node.name, it.wrapType()) }
            }
        }

        override fun addDeleteType() = getOrCreateInputObjectType("${prefix}DeleteInput") { fields, _ ->
            rel.unionNodes.forEach { node ->
                addDeleteFieldInput(node.unionPrefix(), node)?.let { fields += inputValue(node.name, it.wrapType()) }
            }
        }

        override fun addDisconnectType() = getOrCreateInputObjectType("${prefix}DisconnectInput") { fields, _ ->
            rel.unionNodes.forEach { node ->
                addDisconnectFieldInput(node.unionPrefix(), node)?.let {
                    fields += inputValue(node.name, it.wrapType())
                }
            }
        }

        override fun addRelationType() = getOrCreateInputObjectType("${prefix}CreateFieldInput") { fields, _ ->
            rel.unionNodes.forEach { node ->
                addCreateFieldInput(node.unionPrefix(), node)?.let { fields += inputValue(node.name, it.wrapType()) }
            }
        }

        override fun addUpdateType() = getOrCreateInputObjectType("${prefix}UpdateInput") { fields, _ ->
            rel.unionNodes.forEach { node ->
                addUpdateFieldInput(node.unionPrefix(), node)?.let { fields += inputValue(node.name, it.wrapType()) }
            }
        }

        override fun addWhereType() = getOrCreateInputObjectType("${rel.typeMeta.type.name()}Where") { fields, _ ->
            rel.unionNodes.forEach { node ->
                addWhereType(node)?.let { fields += inputValue(node.name, it.asType()) }
            }
        }

        override fun addConnectionWhereType() = getOrCreateInputObjectType("${prefix}ConnectionWhere") { fields, _ ->
            rel.unionNodes.forEach { node ->
                addConnectionWhere(
                    node.unionPrefix(),
                    node,
                    nameOverride = "${prefix}Connection${node.name}Where" // TODO REVIEW Darrell we should harmonize the names in the js version
                )?.let { fields += inputValue(node.name, it.asType()) }
            }
        }

        override fun addConnectOrCreate() = getOrCreateInputObjectType("${prefix}ConnectOrCreateInput") { fields, _ ->
            rel.unionNodes.forEach { node ->
                addConnectOrCreate(node.unionPrefix(), node)?.let { fields += inputValue(node.name, it.wrapType()) }
            }
        }
    }

    /**
     * Augmentation for relations referencing a node
     */
    private inner class NodeAugmentations(
        private val rel: RelationField,
        private val node: Node = rel.node
            ?: throw IllegalArgumentException("no node on ${rel.getOwnerName()}.${rel.fieldName}"),
        private val prefix: String = rel.connectionPrefix + rel.fieldName.capitalize()
    ) : RelationAugmentation(rel) {

        init {
            if (rel.isUnion) {
                throw IllegalArgumentException("The type of ${rel.getOwnerName()}.${rel.fieldName} is not expected to be an union")
            }
            if (rel.isInterface) {
                throw IllegalArgumentException("The type of ${rel.getOwnerName()}.${rel.fieldName} is not expected to be an interface")
            }
        }

        override fun addCreateType() = addNodeFieldInput(prefix, node)

        override fun addConnectType() = addConnectFieldInput(prefix, node)

        override fun addDeleteType() = addDeleteFieldInput(prefix, node)

        override fun addDisconnectType() = addDisconnectFieldInput(prefix, node)

        override fun addRelationType() = addCreateFieldInput(prefix, node)

        override fun addUpdateType() = addUpdateFieldInput(prefix, node)

        override fun addConnectOrCreate() = addConnectOrCreate(prefix, node)

        override fun addWhereType(): String? = addWhereType(node)
        override fun addConnectionWhereType() = addConnectionWhere(prefix, node)
    }

    override fun createDataFetcher(
        operationType: OperationType,
        fieldDefinition: FieldDefinition
    ): DataFetcher<Cypher>? {
        TODO("Not yet implemented")
    }
}
