package org.neo4j.graphql.schema

import graphql.language.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.RelationshipProperties
import org.neo4j.graphql.domain.fields.RelationBaseField
import org.neo4j.graphql.domain.fields.RelationDeclarationField
import org.neo4j.graphql.domain.fields.RelationField
import org.neo4j.graphql.domain.fields.ScalarField
import org.neo4j.graphql.schema.model.inputs.ScalarProperties
import org.neo4j.graphql.schema.relations.InterfaceRelationFieldAugmentations
import org.neo4j.graphql.schema.relations.NodeRelationFieldAugmentations
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import org.neo4j.graphql.schema.relations.UnionRelationFieldAugmentations
import kotlin.reflect.KFunction1

class AugmentationContext(
    val schemaConfig: SchemaConfig,
    internal val typeDefinitionRegistry: TypeDefinitionRegistry,
    val neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
) : AugmentationBase {

    private val inputTypeHandler = HandledTypes(
        { name -> typeDefinitionRegistry.getTypeByName(name) },
        ::inputObjectType
    )

    private val objectTypeHandler = HandledTypes(
        { name -> typeDefinitionRegistry.getTypeByName(name) },
        ::objectType
    )

    private val interfaceTypeHandler = HandledTypes(
        { name -> typeDefinitionRegistry.getTypeByName(name) },
        ::interfaceType
    )

    private val enumTypeHandler = HandledTypes(
        { name -> typeDefinitionRegistry.getTypeByName(name) },
        ::enumType
    )

    private val unionTypeHandler = HandledTypes(
        { name -> typeDefinitionRegistry.getTypeByName(name) },
        ::unionType
    )

    @Suppress("FINITE_BOUNDS_VIOLATION_IN_JAVA")
    private inner class HandledTypes<BUILDER, DEF : TypeDefinition<DEF>, FIELD>(
        val typeResolver: (name: String) -> DEF?,
        val factory: (name: String, fields: List<FIELD>, init: BUILDER) -> DEF
    ) {
        val emptyTypes = mutableSetOf<String>()
        val stack = mutableListOf<String>()

        fun getOrCreateType(
            name: String,
            init: BUILDER,
            initFields: (fields: MutableList<FIELD>, name: String) -> Unit,
        ): String? {
            if (emptyTypes.contains(name)) {
                return null
            }
            val type = typeResolver(name)
            if (type != null) {
                return name
            }
            if (stack.contains(name)) {
                return name
            }
            try {
                stack.add(name)
                val fields = mutableListOf<FIELD>()
                initFields(fields, name)
                return when {
                    fields.isNotEmpty() -> factory(name, fields, init)
                        .also { typeDefinitionRegistry.add(it) }
                        .name

                    else -> {
                        emptyTypes.add(name)
                        null
                    }
                }
            } finally {
                stack.removeLast()
            }

        }
    }

    fun getOrCreateInputObjectType(
        name: String,
        init: (InputObjectTypeDefinition.Builder.() -> Unit)? = null,
        initFields: (fields: MutableList<InputValueDefinition>, name: String) -> Unit
    ): String? = inputTypeHandler.getOrCreateType(name, init, initFields)

    private fun inputObjectType(
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

    fun getOrCreateObjectType(
        name: String,
        init: (ObjectTypeDefinition.Builder.() -> Unit)? = null,
        initFields: (fields: MutableList<FieldDefinition>, name: String) -> Unit,
    ): String? = objectTypeHandler.getOrCreateType(name, init, initFields)

    private fun objectType(
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

    fun getOrCreateInterfaceType(
        name: String,
        init: (InterfaceTypeDefinition.Builder.() -> Unit)?,
        initFields: (fields: MutableList<FieldDefinition>, name: String) -> Unit,
    ): String? = interfaceTypeHandler.getOrCreateType(name, init, initFields)

    private fun interfaceType(
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

    fun getOrCreateEnumType(
        name: String,
        init: (EnumTypeDefinition.Builder.() -> Unit)? = null,
        initValues: (enumValues: MutableList<EnumValueDefinition>, name: String) -> Unit
    ): String? = enumTypeHandler.getOrCreateType(name, init, initValues)

    private fun enumType(
        name: String,
        values: List<EnumValueDefinition>,
        init: (EnumTypeDefinition.Builder.() -> Unit)? = null
    ): EnumTypeDefinition {
        val type = EnumTypeDefinition.newEnumTypeDefinition()
        type.name(name)
        type.enumValueDefinitions(values)
        init?.let { type.it() }
        return type.build()
    }

    fun getOrCreateUnionType(
        name: String,
        init: (UnionTypeDefinition.Builder.() -> Unit)? = null,
        initValues: (members: MutableList<Type<*>>, name: String) -> Unit
    ): String? = unionTypeHandler.getOrCreateType(name, init, initValues)

    private fun unionType(
        name: String,
        values: List<Type<*>>,
        init: (UnionTypeDefinition.Builder.() -> Unit)? = null
    ): UnionTypeDefinition {
        val type = UnionTypeDefinition.newUnionTypeDefinition()
        type.name(name)
        type.memberTypes(values)
        init?.let { type.it() }
        return type.build()
    }

    fun getOrCreateRelationInputObjectType(
        name: String,
        relationFields: List<RelationBaseField>,
        extractor: (RelationFieldBaseAugmentation) -> String?,
        wrapList: Boolean = true,
        scalarFields: List<ScalarField> = emptyList(),
        update: Boolean = false,
        enforceFields: Boolean = false,
        condition: (RelationBaseField) -> Boolean = { true }
    ): String? = getOrCreateInputObjectType(name) { fields, _ ->

        ScalarProperties.Companion.Augmentation
            .addScalarFields(fields, scalarFields, update, this)

        relationFields
            .filter { condition(it) }
            .forEach { rel ->
                getTypeFromRelationField(rel, extractor)?.let { typeName ->
                    val type = if (!rel.isUnion && wrapList) {
                        // for union fields, the arrays are moved down one level, so we don't wrap them here
                        typeName.wrapType(rel)
                    } else {
                        typeName.asType()
                    }
                    fields += inputValue(rel.fieldName, type) { rel.deprecatedDirective?.let { directive(it) } }

                }
            }
        if (fields.isEmpty() && enforceFields) {
            fields += emptyInputField()
        }
    }

    fun emptyInputField() = inputValue(Constants.EMPTY_INPUT, Constants.Types.Boolean) {
        // TODO use a link of this project
        description("Appears because this input type would be empty otherwise because this type is composed of just generated and/or relationship properties. See https://neo4j.com/docs/graphql-manual/current/troubleshooting/faqs/".asDescription())
    }

    fun getTypeFromRelationField(
        rel: RelationBaseField,
        extractor: (RelationFieldBaseAugmentation) -> String?
    ): String? {
        val aug = rel.extractOnTarget(
            onNode = { NodeRelationFieldAugmentations(this, rel, it) },
            onInterface = { InterfaceRelationFieldAugmentations(this, rel, it) },
            onUnion = { UnionRelationFieldAugmentations(this, rel, it) }
        )
        return extractor(aug)
    }

    fun addInterfaceField(
        interfaze: Interface,
        name: String,
        relationFieldsResolver: KFunction1<RelationFieldBaseAugmentation, String?>,
        addAdditionalFields: ((fields: MutableList<InputValueDefinition>) -> Unit)? = null,
    ) = getOrCreateInputObjectType(name) { fields, _ ->
        interfaze.relationBaseFields.forEach { r ->
            getTypeFromRelationField(r, relationFieldsResolver)
                ?.let { fields += inputValue(r.fieldName, it.wrapType(r)) }
        }
        addAdditionalFields?.invoke(fields)
    }

    fun addTypenameEnum(
        interfaze: Interface,
        fields: MutableList<InputValueDefinition>,
    ) {
        getOrCreateEnumType(interfaze.namings.implementationEnumTypename) { enumValues, _ ->
            interfaze.implementations.values.forEach { node ->
                enumValues += EnumValueDefinition(node.name)
            }
        }?.let {
            fields += inputValue(Constants.TYPENAME_IN, ListType(it.asRequiredType()))
        }
    }

    fun generateImplementationDelegate(
        interfaze: Interface,
        name: String,
        asList: Boolean,
        getNodeType: (Node) -> String?,
        getAdditionalFields: (() -> List<InputValueDefinition>)? = null,
    ) =
        getOrCreateInputObjectType(name) { fields, _ ->
            interfaze.implementations.values.forEach { node ->
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

    fun getEdgeInputField(
        relationField: RelationBaseField,
        getDeclarationName: (RelationDeclarationField) -> String,
        required: (RelationshipProperties) -> Boolean = { false },
        onRelationField: (relationField: RelationField) -> String?
    ) = when (relationField) {
        is RelationField -> onRelationField(relationField)?.asType(relationField.properties?.let(required) ?: false)
        is RelationDeclarationField -> {
            getOrCreateInputObjectType(getDeclarationName(relationField), initFields = { fields, _ ->
                relationField.relationshipImplementations.groupBy { it.properties?.typeName }
                    .forEach { (propsTypeName, rels) ->
                        if (propsTypeName == null) {
                            return@forEach
                        }
                        val firstRel = rels.first()
                        onRelationField(firstRel)
                            ?.let { name ->
                                fields += inputValue(propsTypeName, name.asType(required(firstRel.properties!!))) {
                                    description(
                                        "Relationship properties when source node is of type:\n${rels.joinToString("\n") { "* " + it.getOwnerName() }}".asDescription()
                                    )
                                }
                            }
                    }
            })?.asType(relationField.relationshipImplementations.mapNotNull { it.properties }.any(required))
        }
    }
}
