package org.neo4j.graphql

import graphql.language.*
import graphql.schema.FieldCoordinates
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.domain.inputs.ScalarProperties
import org.neo4j.graphql.schema.relations.InterfaceRelationFieldAugmentations
import org.neo4j.graphql.schema.relations.NodeRelationFieldAugmentations
import org.neo4j.graphql.schema.relations.RelationFieldBaseAugmentation
import org.neo4j.graphql.schema.relations.UnionRelationFieldAugmentations
import kotlin.reflect.KFunction1

interface AugmentationBase {

    fun getOrCreateInputObjectType(
        name: String,
        init: (InputObjectTypeDefinition.Builder.() -> Unit)? = null,
        initFields: (fields: MutableList<InputValueDefinition>, name: String) -> Unit
    ): String?


    fun addQueryField(fieldDefinition: FieldDefinition): FieldCoordinates

    fun addQueryField(
        name: String,
        type: Type<*>,
        args: ((MutableList<InputValueDefinition>) -> Unit)?
    ): FieldCoordinates {
        val argList = mutableListOf<InputValueDefinition>()
        args?.invoke(argList)
        return addQueryField(field(name, type, argList))
    }

    fun addMutationField(fieldDefinition: FieldDefinition): FieldCoordinates

    fun addMutationField(
        name: String,
        type: Type<*>,
        args: ((MutableList<InputValueDefinition>) -> Unit)?
    ): FieldCoordinates {
        val argList = mutableListOf<InputValueDefinition>()
        args?.invoke(argList)
        return addMutationField(field(name, type, argList))
    }

    fun getOrCreateObjectType(
        name: String,
        init: (ObjectTypeDefinition.Builder.() -> Unit)? = null,
        initFields: (fields: MutableList<FieldDefinition>, name: String) -> Unit,
    ): String?

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

    fun getOrCreateRelationInputObjectType(
        sourceName: String,
        suffix: String,
        relationFields: List<RelationField>,
        extractor: KFunction1<RelationFieldBaseAugmentation, String?>,
        wrapList: Boolean = true,
        scalarFields: List<ScalarField> = emptyList(),
        update: Boolean = false,
        enforceFields: Boolean = false,
    ): String?

    fun getTypeFromRelationField(
        rel: RelationField,
        extractor: KFunction1<RelationFieldBaseAugmentation, String?>
    ): String?

    fun directedArgument(relationshipField: RelationField): InputValueDefinition?
}

class AugmentationContext(
    val schemaConfig: SchemaConfig,
    val typeDefinitionRegistry: TypeDefinitionRegistry,
    val neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
) : AugmentationBase {

    private val knownInputTypes = mutableSetOf<String>()
    private val emptyInputTypes = mutableSetOf<String>()

    private val knownObjectTypes = mutableSetOf<String>()
    private val emptyObjectTypes = mutableSetOf<String>()

    override fun getOrCreateInputObjectType(
        name: String,
        init: (InputObjectTypeDefinition.Builder.() -> Unit)?,
        initFields: (fields: MutableList<InputValueDefinition>, name: String) -> Unit
    ): String? {
        if (emptyInputTypes.contains(name)) {
            return null
        }
        if (knownInputTypes.contains(name)) {
            // TODO we should use futures here, so we can handle null names, which we do not know at this point of time
            return name
        }
        val type = typeDefinitionRegistry.getTypeByName<InterfaceTypeDefinition>(name)
        return when (type != null) {
            true -> type
            else -> {
                val fields = mutableListOf<InputValueDefinition>()
                knownInputTypes.add(name)
                initFields(fields, name)
                if (fields.isNotEmpty()) {
                    inputObjectType(name, fields, init).also { typeDefinitionRegistry.add(it) }
                } else {
                    emptyInputTypes.add(name)
                    null
                }
            }
        }?.name
    }

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

    override fun addQueryField(fieldDefinition: FieldDefinition): FieldCoordinates {
        val queryTypeName = typeDefinitionRegistry.queryTypeName()
        addOperation(queryTypeName, fieldDefinition)
        return FieldCoordinates.coordinates(queryTypeName, fieldDefinition.name)
    }

    override fun addMutationField(fieldDefinition: FieldDefinition): FieldCoordinates {
        val mutationTypeName = typeDefinitionRegistry.mutationTypeName()
        addOperation(mutationTypeName, fieldDefinition)
        return FieldCoordinates.coordinates(mutationTypeName, fieldDefinition.name)
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


    override fun getOrCreateObjectType(
        name: String,
        init: (ObjectTypeDefinition.Builder.() -> Unit)?,
        initFields: (fields: MutableList<FieldDefinition>, name: String) -> Unit,
    ): String? {
        if (emptyObjectTypes.contains(name)) {
            return null
        }
        if (knownObjectTypes.contains(name)) {
            // TODO we should use futures here, so we can handle null names, which we do not know at this point of time
            return name
        }
        val type = typeDefinitionRegistry.getTypeByName<ObjectTypeDefinition>(name)
        return when (type != null) {
            true -> type
            else -> {
                val fields = mutableListOf<FieldDefinition>()
                knownObjectTypes.add(name)
                initFields(fields, name)
                if (fields.isNotEmpty()) {
                    objectType(name, fields, init).also { typeDefinitionRegistry.add(it) }
                } else {
                    emptyObjectTypes.add(name)
                    null
                }
            }
        }?.name
    }

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
    override fun getOrCreateRelationInputObjectType(
        sourceName: String,
        suffix: String,
        relationFields: List<RelationField>,
        extractor: KFunction1<RelationFieldBaseAugmentation, String?>,
        wrapList: Boolean,
        scalarFields: List<ScalarField>,
        update: Boolean,
        enforceFields: Boolean,
    ) = getOrCreateInputObjectType(sourceName + suffix) { fields, _ ->

        ScalarProperties.Companion.Augmentation
            .addScalarFields(fields, sourceName, scalarFields, update, this)

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

    override fun getTypeFromRelationField(
        rel: RelationField,
        extractor: KFunction1<RelationFieldBaseAugmentation, String?>
    ): String? {
        val aug = rel.extractOnTarget(
            onNode = { NodeRelationFieldAugmentations(this, rel, it) },
            onInterface = { InterfaceRelationFieldAugmentations(this, rel, it) },
            onUnion = { UnionRelationFieldAugmentations(this, rel, it) }
        )
        return extractor(aug)
    }

    override fun directedArgument(relationshipField: RelationField): InputValueDefinition? =
        when (relationshipField.queryDirection) {
            RelationField.QueryDirection.DEFAULT_DIRECTED -> true
            RelationField.QueryDirection.DEFAULT_UNDIRECTED -> false
            else -> null
        }?.let { defaultVal ->
            inputValue(Constants.DIRECTED, Constants.Types.Boolean) { defaultValue(BooleanValue(defaultVal)) }
        }
}
