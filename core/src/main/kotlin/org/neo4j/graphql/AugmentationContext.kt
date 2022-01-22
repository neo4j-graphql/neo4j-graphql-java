package org.neo4j.graphql

import graphql.language.*
import graphql.schema.idl.TypeDefinitionRegistry
import org.neo4j.graphql.domain.fields.RelationField

interface AugmentationBase {

    fun getOrCreateInputObjectType(
        name: String,
        init: (InputObjectTypeDefinition.Builder.() -> Unit)? = null,
        initFields: (fields: MutableList<InputValueDefinition>, name: String) -> Unit
    ): String?


    fun addQueryField(fieldDefinition: FieldDefinition)

    fun addQueryField(name: String, type: Type<*>, args: ((MutableList<InputValueDefinition>) -> Unit)?) {
        val argList = mutableListOf<InputValueDefinition>()
        args?.invoke(argList)
        addQueryField(field(name, type, argList))
    }

    fun addMutationField(fieldDefinition: FieldDefinition)

    fun addMutationField(name: String, type: Type<*>, args: ((MutableList<InputValueDefinition>) -> Unit)?) {
        val argList = mutableListOf<InputValueDefinition>()
        args?.invoke(argList)
        addMutationField(field(name, type, argList))
    }

    fun addInputObjectType(
        name: String,
        vararg fields: InputValueDefinition,
        init: (InputObjectTypeDefinition.Builder.() -> Unit)? = null
    ): String = addInputObjectType(name, fields.asList(), init)


    fun addInputObjectType(
        name: String,
        fields: List<InputValueDefinition>,
        init: (InputObjectTypeDefinition.Builder.() -> Unit)? = null
    ): String

    fun addObjectType(
        name: String,
        vararg fields: FieldDefinition,
        init: (ObjectTypeDefinition.Builder.() -> Unit)? = null
    ): String = addObjectType(name, fields.asList(), init)

    fun addObjectType(
        name: String,
        fields: List<FieldDefinition>,
        init: (ObjectTypeDefinition.Builder.() -> Unit)? = null
    ): String

    fun getOrCreateObjectType(
        name: String,
        init: (ObjectTypeDefinition.Builder.() -> Unit)? = null,
        initFields: (fields: MutableList<FieldDefinition>, name: String) -> Unit,
    ): String?

    fun getOrCreateInterfaceType(
        name: String,
        init: (InterfaceTypeDefinition.Builder.() -> Unit)? = null,
        initFields: (fields: MutableList<FieldDefinition>, name: String) -> Unit,
    ): String?

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

class AugmentationContext(
    val schemaConfig: SchemaConfig,
    val typeDefinitionRegistry: TypeDefinitionRegistry,
    val neo4jTypeDefinitionRegistry: TypeDefinitionRegistry
) : AugmentationBase {

    private val currentlyCreating = mutableSetOf<String>()

    override fun getOrCreateInputObjectType(
        name: String,
        init: (InputObjectTypeDefinition.Builder.() -> Unit)?,
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

    override fun addQueryField(fieldDefinition: FieldDefinition) {
        addOperation(typeDefinitionRegistry.queryTypeName(), fieldDefinition)
    }

    override fun addMutationField(fieldDefinition: FieldDefinition) {
        addOperation(typeDefinitionRegistry.mutationTypeName(), fieldDefinition)
    }

    /**
     * add the given operation to the corresponding rootType
     */
    fun addOperation(rootTypeName: String, fieldDefinition: FieldDefinition) {
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

    override fun addInputObjectType(
        name: String,
        fields: List<InputValueDefinition>,
        init: (InputObjectTypeDefinition.Builder.() -> Unit)?
    ): String {
        val type = typeDefinitionRegistry.getTypeByName(name)
            ?: inputObjectType(name, fields, init).also { typeDefinitionRegistry.add(it) }
        return type.name
    }

    override fun addObjectType(
        name: String,
        fields: List<FieldDefinition>,
        init: (ObjectTypeDefinition.Builder.() -> Unit)?
    ): String {
        val type = typeDefinitionRegistry.getTypeByName(name)
            ?: objectType(name, fields, init).also { typeDefinitionRegistry.add(it) }
        return type.name
    }

    override fun getOrCreateObjectType(
        name: String,
        init: (ObjectTypeDefinition.Builder.() -> Unit)?,
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

    override fun getOrCreateInterfaceType(
        name: String,
        init: (InterfaceTypeDefinition.Builder.() -> Unit)?,
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
}
