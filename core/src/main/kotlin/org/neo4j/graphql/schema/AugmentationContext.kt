package org.neo4j.graphql.schema

import graphql.language.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Interface
import org.neo4j.graphql.domain.Node
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
        val knownTypes = mutableSetOf<String>()
        val emptyTypes = mutableSetOf<String>()

        fun getOrCreateType(
            name: String,
            init: BUILDER,
            initFields: (fields: MutableList<FIELD>, name: String) -> Unit
        ): String? {
            if (emptyTypes.contains(name)) {
                return null
            }
            if (knownTypes.contains(name)) {
                // TODO we should use futures here, so we can handle null names, which we do not know at this point of time
                return name
            }
            val type = typeResolver(name)
            return when (type != null) {
                true -> type
                else -> {
                    val fields = mutableListOf<FIELD>()
                    knownTypes.add(name)
                    initFields(fields, name)
                    if (fields.isNotEmpty()) {
                        factory(name, fields, init).also { typeDefinitionRegistry.add(it) }
                    } else {
                        emptyTypes.add(name)
                        null
                    }
                }
            }?.name
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
        relationFields: List<RelationField>,
        extractor: (RelationFieldBaseAugmentation) -> String?,
        wrapList: Boolean = true,
        scalarFields: List<ScalarField> = emptyList(),
        update: Boolean = false,
        enforceFields: Boolean = false,
        condition: (RelationField) -> Boolean = { true }
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
        rel: RelationField,
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
        onTypename: String,
        implementationResolver: (Node) -> String?,
        relationFieldsResolver: KFunction1<RelationFieldBaseAugmentation, String?>,
        asList: Boolean = true,
        getAdditionalFields: (() -> List<InputValueDefinition>)? = null,
    ) = getOrCreateInputObjectType(name) { fields, _ ->
        addOnField(interfaze, onTypename, fields, asList, implementationResolver)
        interfaze.relationFields.forEach { r ->
            getTypeFromRelationField(r, relationFieldsResolver)
                ?.let { fields += inputValue(r.fieldName, it.wrapType(r)) }
        }
        getAdditionalFields?.invoke()?.let { fields += it }
    }

    fun addOnField(
        interfaze: Interface,
        name: String,
        fields: MutableList<InputValueDefinition>,
        asList: Boolean,
        getNodeType: (Node) -> String?
    ) {
        generateImplementationDelegate(interfaze, name, asList = asList, getNodeType)?.let {
            fields += inputValue(Constants.ON, it.asType())
        }
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
}
